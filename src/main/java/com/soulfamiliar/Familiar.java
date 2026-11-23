package com.soulfamiliar;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Rotations;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public class Familiar {
    private static int nextFakeEntityId = -91000;

    private final String name;
    private final ItemStack skullStack;
    private ArmorStand etherealVisual;
    private Vec3 currentSkullPos = null;
    private UUID boundStandUuid;
    private FamiliarMode mode = FamiliarMode.IDLE;
    private final PoseState currentPose = new PoseState();
    private boolean initializedFlags = false;

    // Autonomous roaming for ethereal skull
    private boolean roamEnabled = true;
    private Vec3 roamGoal = null;
    private int roamPauseTicks = 0;
    private Vec3 inspectFocusPos = null;
    private int inspectTicksLeft = 0;

    // Action state
    private int actionTimer = 0;
    private Vec3 pointTarget = null;
    private UUID scareTargetUuid = null;
    private Vec3 scareOrigin = null;
    private int scarePhase = 0;
    private UUID stalkTargetUuid = null;
    private Vec3 skullOverridePos = null;
    private float lastPlayerHealth = -1.0f;
    private int startleTicks = 0;

    public Familiar(String name, ItemStack skullStack) {
        this.name = name;
        this.skullStack = skullStack.copy();
        this.skullStack.setCount(1);
    }

    public String getName() {
        return name;
    }

    public FamiliarMode getMode() {
        return mode;
    }

    public UUID getBoundStandUuid() {
        return boundStandUuid;
    }

    public boolean isBound() {
        return boundStandUuid != null;
    }

    public void setRoamEnabled(boolean enabled) {
        this.roamEnabled = enabled;
        this.roamGoal = null;
        this.inspectFocusPos = null;
        this.inspectTicksLeft = 0;
    }

    public void focusOnLocation(Vec3 pos, int ticks) {
        this.inspectFocusPos = pos.add(0, 0.85, 0);
        this.inspectTicksLeft = ticks;
    }

    public void setSkullOverridePos(Vec3 pos) {
        this.skullOverridePos = pos;
    }

    public Vec3 getCurrentWorldPosition(ClientLevel level, LocalPlayer owner) {
        ArmorStand stand = resolveBoundStand(level);
        if (stand != null) {
            return stand.position().add(0, 1.5, 0);
        }
        if (currentSkullPos != null) {
            return currentSkullPos;
        }
        return owner != null ? owner.getEyePosition() : Vec3.ZERO;
    }

    public ArmorStand resolveBoundStand(ClientLevel level) {
        if (boundStandUuid == null || level == null) return null;
        for (Entity entity : level.entitiesForRendering()) {
            if (entity instanceof ArmorStand stand && entity.getUUID().equals(boundStandUuid)) {
                return stand;
            }
        }
        return null;
    }

    public void bindToStand(Minecraft client, ArmorStand stand) {
        if (client.level == null || stand == null) return;
        this.boundStandUuid = stand.getUUID();
        this.initializedFlags = false;
        this.mode = FamiliarMode.IDLE;
        this.currentPose.bodyYaw = stand.getYRot();

        Vec3 headPos = stand.position().add(0, 1.65, 0);
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        for (int i = 0; i < 24; i++) {
            double ox = (rng.nextDouble() - 0.5) * 0.7;
            double oy = (rng.nextDouble() - 0.5) * 0.7;
            double oz = (rng.nextDouble() - 0.5) * 0.7;
            client.level.addParticle(ParticleTypes.SOUL_FIRE_FLAME, headPos.x + ox, headPos.y + oy, headPos.z + oz, 0, 0.03, 0);
        }
        client.level.playLocalSound(headPos.x, headPos.y, headPos.z, SoundEvents.SOUL_ESCAPE.value(), SoundSource.PLAYERS, 0.9f, 0.85f, false);
        discardEtherealVisual();
    }

    public void releaseStand(Minecraft client) {
        if (client.level != null && boundStandUuid != null) {
            ArmorStand stand = resolveBoundStand(client.level);
            if (stand != null) {
                Vec3 pos = stand.position().add(0, 1.65, 0);
                this.currentSkullPos = pos;
                ThreadLocalRandom rng = ThreadLocalRandom.current();
                for (int i = 0; i < 16; i++) {
                    client.level.addParticle(ParticleTypes.SOUL, pos.x, pos.y, pos.z,
                            (rng.nextDouble() - 0.5) * 0.08,
                            0.05,
                            (rng.nextDouble() - 0.5) * 0.08);
                }
            }
        }
        this.boundStandUuid = null;
        this.mode = FamiliarMode.IDLE;
    }

    public void setMode(FamiliarMode newMode) {
        this.mode = newMode;
        this.actionTimer = 0;
    }

    public void triggerBow(Vec3 lookTarget) {
        this.mode = FamiliarMode.BOW;
        this.pointTarget = lookTarget;
        this.actionTimer = 55;
    }

    public void triggerPoint(Vec3 target) {
        this.mode = FamiliarMode.POINT;
        this.pointTarget = target;
        this.actionTimer = 75;
        this.inspectFocusPos = target.add(0, 0.9, 0);
        this.inspectTicksLeft = 90;
    }

    public void triggerNod() {
        this.mode = FamiliarMode.NOD;
        this.actionTimer = 48;
    }

    public void triggerShakeHead() {
        this.mode = FamiliarMode.SHAKE_HEAD;
        this.actionTimer = 48;
    }

    public void triggerStalk(Entity target) {
        if (target == null) return;
        this.stalkTargetUuid = target.getUUID();
        this.mode = FamiliarMode.STALK;
        this.actionTimer = 0;
    }

    public void triggerScare(Player targetPlayer, ArmorStand currentStand) {
        if (targetPlayer == null) return;
        this.mode = FamiliarMode.SCARE;
        this.scareTargetUuid = targetPlayer.getUUID();
        this.scareOrigin = currentStand != null ? currentStand.position() : null;
        this.scarePhase = 0;
        this.actionTimer = 0;
    }

    public void speakSurroundings(Minecraft client) {
        if (client.level == null || client.player == null) return;
        Vec3 origin = getCurrentWorldPosition(client.level, client.player);
        String report = WorldScout.describeSurroundings(client.level, client.player, origin);
        speakToOwner(client, report);
    }

    public void locateAndReport(Minecraft client, String query) {
        if (client.level == null || client.player == null) return;
        Vec3 origin = getCurrentWorldPosition(client.level, client.player);
        WorldScout.SearchResult res = WorldScout.locateTarget(client.level, client.player, origin, query);
        if (res == null) {
            speakToOwner(client, "Я всё облетел, но «" + query + "» поблизости нет. Попробуй поискать сам, у тебя же две ноги.");
            triggerShakeHead();
            return;
        }
        int dist = (int) Math.round(res.distance());
        speakToOwner(client, "Нашёл! Это " + res.label() + " — примерно " + dist + " бл. " + res.direction() + ". Не благодари.");

        if (isBound()) {
            triggerPoint(res.pos());
        } else {
            focusOnLocation(res.pos(), 110);
        }
    }

    public void speakToOwner(Minecraft client, String line) {
        FamiliarManager.notifyDialogue(client, this.name, line);
    }

    public void tick(Minecraft client, int index, int totalEthereal, int groupIndex, int groupTotal, long tickCount, boolean canSendPacket) {
        ClientLevel level = client.level;
        LocalPlayer player = client.player;
        if (level == null || player == null) return;

        float hp = player.getHealth();
        if (lastPlayerHealth > 0 && hp < lastPlayerHealth) {
            startleTicks = 25;
        }
        lastPlayerHealth = hp;
        if (startleTicks > 0) startleTicks--;

        ArmorStand stand = resolveBoundStand(level);

        if (stand == null) {
            tickEtherealSkull(level, player, index, totalEthereal, groupIndex, groupTotal, tickCount);
            return;
        } else {
            discardEtherealVisual();
        }

        if (mode == FamiliarMode.FROZEN) {
            return;
        }

        if (player.distanceToSqr(stand) > 169.0) {
            return;
        }

        Vec3 standPos = stand.position();
        Vec3 moveDelta = Vec3.ZERO;
        PoseState desired = PoseState.idle(currentPose.bodyYaw, tickCount);

        switch (mode) {
            case POSSESSED -> {
                float pYaw = player.getYRot();
                float pPitch = player.getXRot();
                desired = PoseState.idle(pYaw, tickCount);
                desired.headX = Mth.clamp(pPitch, -55.0f, 55.0f);
                desired.headY = 0.0f;

                double forward = 0;
                double strafe = 0;
                double up = 0;
                if (client.options.keyUp.isDown()) forward += 0.25;
                if (client.options.keyDown.isDown()) forward -= 0.25;
                if (client.options.keyLeft.isDown()) strafe += 0.25;
                if (client.options.keyRight.isDown()) strafe -= 0.25;
                if (client.options.keyJump.isDown()) up += 0.22;
                if (client.options.keyShift.isDown()) up -= 0.22;

                if (forward != 0 || strafe != 0 || up != 0) {
                    float rad = pYaw * Mth.DEG_TO_RAD;
                    double mx = (-Mth.sin(rad) * forward + Mth.cos(rad) * strafe);
                    double mz = (Mth.cos(rad) * forward + Mth.sin(rad) * strafe);
                    moveDelta = new Vec3(mx, up, mz);
                    desired = PoseState.sprint(pYaw, tickCount * 0.6f);
                    desired.headX = Mth.clamp(pPitch, -55.0f, 55.0f);
                }
            }
            case IDLE -> {
                if (startleTicks > 0) {
                    float yawToPlayer = yawBetween(standPos, player.position());
                    desired = PoseState.jumpscare(yawToPlayer);
                    desired.bodyX = -8.0f;
                } else {
                    double distSq = player.distanceToSqr(stand);
                    if (distSq < 16.0 && isPlayerLookingNear(player, standPos.add(0, 1.5, 0), 0.88)) {
                        float yawToOwner = yawBetween(standPos, player.position());
                        desired = PoseState.bow(yawToOwner);
                    } else {
                        desired = PoseState.idle(currentPose.bodyYaw, tickCount);
                    }
                }
            }
            case BOW -> {
                Vec3 target = pointTarget != null ? pointTarget : player.position();
                desired = PoseState.bow(yawBetween(standPos, target));
                if (--actionTimer <= 0) mode = FamiliarMode.IDLE;
            }
            case POINT -> {
                Vec3 target = pointTarget != null ? pointTarget : player.position();
                float yaw = yawBetween(standPos, target);
                float pitch = pitchBetween(standPos.add(0, 1.5, 0), target);
                desired = PoseState.point(yaw, pitch);
                if (--actionTimer <= 0) mode = FamiliarMode.IDLE;
            }
            case GUARD -> {
                Entity threat = findNearestThreat(level, player, standPos, 10.0);
                if (threat != null) {
                    float yaw = yawBetween(standPos, threat.position());
                    float pitch = pitchBetween(standPos.add(0, 1.5, 0), threat.getEyePosition());
                    desired = PoseState.guard(yaw, pitch, 0.0f, tickCount);
                } else {
                    float scanYaw = currentPose.bodyYaw + Mth.sin(tickCount * 0.05f) * 35.0f;
                    desired = PoseState.guard(currentPose.bodyYaw, 0.0f, scanYaw - currentPose.bodyYaw, tickCount);
                }
            }
            case FOLLOW -> {
                float rad = (player.getYRot() + 55.0f + groupIndex * 32.0f) * Mth.DEG_TO_RAD;
                Vec3 desiredPos = player.position().add(-Mth.sin(rad) * 1.5, 0.45 + Math.sin(tickCount * 0.1) * 0.12, Mth.cos(rad) * 1.5);
                Vec3 diff = desiredPos.subtract(standPos);
                if (diff.length() > 0.15) {
                    moveDelta = diff.scale(0.35);
                }
                desired = PoseState.idle(player.getYRot(), tickCount);
                desired.leftLegX = 14.0f;
                desired.rightLegX = 22.0f;
            }
            case KNEEL -> desired = PoseState.kneel(yawBetween(standPos, player.position()));
            case MEDITATE -> {
                double hoverY = player.getY() + 0.65 + Math.sin(tickCount * 0.08) * 0.14;
                double dy = hoverY - standPos.y;
                if (Math.abs(dy) > 0.04) {
                    moveDelta = new Vec3(0, dy * 0.25, 0);
                }
                desired = PoseState.meditate(currentPose.bodyYaw, tickCount);
            }
            case APPLAUD -> desired = PoseState.applaud(yawBetween(standPos, player.position()), tickCount);
            case THREATEN -> desired = PoseState.threaten(yawBetween(standPos, player.position()), tickCount);
            case THINK -> desired = PoseState.think(currentPose.bodyYaw, tickCount);
            case DRAMA -> desired = PoseState.drama(yawBetween(standPos, player.position()), tickCount);
            case NOD -> {
                desired = PoseState.nod(yawBetween(standPos, player.position()), tickCount);
                if (--actionTimer <= 0) mode = FamiliarMode.IDLE;
            }
            case SHAKE_HEAD -> {
                desired = PoseState.shakeHead(yawBetween(standPos, player.position()), tickCount);
                if (--actionTimer <= 0) mode = FamiliarMode.IDLE;
            }
            case DANCE -> {
                desired = PoseState.dance(player.getYRot() + 180.0f, tickCount);
                if (tickCount % 6 == 0) {
                    ThreadLocalRandom rng = ThreadLocalRandom.current();
                    level.addParticle(ParticleTypes.NOTE,
                            standPos.x + (rng.nextDouble() - 0.5) * 1.1,
                            standPos.y + 1.9 + rng.nextDouble() * 0.4,
                            standPos.z + (rng.nextDouble() - 0.5) * 1.1,
                            rng.nextDouble(), 0.0, 0.0);
                }
            }
            case FORMATION_CIRCLE -> {
                double angle = (Math.PI * 2.0 * groupIndex) / Math.max(1, groupTotal);
                double radius = 2.6;
                Vec3 slot = new Vec3(
                        player.getX() + Math.cos(angle) * radius,
                        player.getY(),
                        player.getZ() + Math.sin(angle) * radius
                );
                Vec3 diff = slot.subtract(standPos);
                if (diff.length() > 0.15) {
                    moveDelta = diff.scale(0.35);
                    desired = PoseState.sprint(yawBetween(standPos, slot), tickCount);
                } else {
                    float outwardYaw = yawBetween(player.position(), standPos);
                    desired = PoseState.guard(outwardYaw, 0.0f, 0.0f, tickCount);
                }
            }
            case FORMATION_LINE -> {
                float yawRad = player.getYRot() * Mth.DEG_TO_RAD;
                Vec3 forward = new Vec3(-Mth.sin(yawRad), 0, Mth.cos(yawRad));
                Vec3 right = new Vec3(Mth.cos(yawRad), 0, Mth.sin(yawRad));
                double offset = (groupIndex - (groupTotal - 1) * 0.5) * 1.35;
                Vec3 slot = player.position().add(forward.scale(2.4)).add(right.scale(offset));
                Vec3 diff = slot.subtract(standPos);
                if (diff.length() > 0.15) {
                    moveDelta = diff.scale(0.35);
                    desired = PoseState.sprint(yawBetween(standPos, slot), tickCount);
                } else {
                    desired = PoseState.guard(player.getYRot(), 0.0f, 0.0f, tickCount);
                }
            }
            case MARCH -> {
                float yawRad = player.getYRot() * Mth.DEG_TO_RAD;
                Vec3 back = new Vec3(Mth.sin(yawRad), 0, -Mth.cos(yawRad));
                Vec3 right = new Vec3(Mth.cos(yawRad), 0, Mth.sin(yawRad));
                int row = groupIndex / 2;
                double side = (groupTotal == 1) ? 0.0 : ((groupIndex % 2 == 0) ? -0.75 : 0.75);
                Vec3 slot = player.position().add(back.scale(1.8 + row * 1.3)).add(right.scale(side));
                Vec3 diff = slot.subtract(standPos);
                boolean moving = diff.length() > 0.22;
                if (moving) {
                    moveDelta = diff.scale(0.38);
                }
                desired = PoseState.march(player.getYRot(), tickCount, moving);
            }
            case STALK -> {
                Entity target = findEntityByUuid(level, stalkTargetUuid);
                if (target == null || !target.isAlive()) {
                    mode = FamiliarMode.IDLE;
                    break;
                }
                Vec3 behind = target.position().subtract(target.getLookAngle().scale(1.85));
                Vec3 goal = new Vec3(behind.x, target.getY(), behind.z);
                Vec3 diff = goal.subtract(standPos);
                float yawToTarget = yawBetween(standPos, target.position());

                boolean targetWatching = (target instanceof Player p) && isPlayerLookingNear(p, standPos.add(0, 1.5, 0), 0.78);
                if (targetWatching) {
                    desired = PoseState.guard(yawToTarget, 0.0f, 0.0f, 0.0f);
                } else if (diff.length() > 0.35) {
                    moveDelta = diff.normalize().scale(Math.min(diff.length(), 0.48));
                    desired = PoseState.sprint(yawToTarget, tickCount);
                } else {
                    desired = PoseState.guard(yawToTarget, 0.0f, 0.0f, tickCount);
                }
            }
            case SCARE -> {
                Player victim = scareTargetUuid != null ? level.getPlayerByUUID(scareTargetUuid) : null;
                if (victim == null) {
                    mode = FamiliarMode.IDLE;
                    break;
                }
                if (scarePhase == 0) {
                    Vec3 frontOfVictim = victim.position().add(victim.getLookAngle().scale(1.15));
                    Vec3 targetPos = new Vec3(frontOfVictim.x, victim.getY(), frontOfVictim.z);
                    Vec3 toTarget = targetPos.subtract(standPos);
                    double dist = toTarget.length();
                    float yaw = yawBetween(standPos, victim.position());
                    if (dist > 0.45 && actionTimer++ < 25) {
                        double step = Math.min(dist, 0.95);
                        moveDelta = toTarget.normalize().scale(step);
                        desired = PoseState.sprint(yaw, tickCount * 1.6f);
                    } else {
                        scarePhase = 1;
                        actionTimer = 32;
                        desired = PoseState.jumpscare(yaw);
                    }
                } else if (scarePhase == 1) {
                    float yaw = yawBetween(standPos, victim.position());
                    desired = PoseState.jumpscare(yaw);
                    if (--actionTimer <= 0) {
                        scarePhase = 2;
                        actionTimer = 0;
                    }
                } else {
                    if (scareOrigin != null) {
                        Vec3 back = scareOrigin.subtract(standPos);
                        double dist = back.length();
                        if (dist > 0.3 && actionTimer++ < 30) {
                            moveDelta = back.normalize().scale(Math.min(dist, 0.65));
                            desired = PoseState.idle(yawBetween(standPos, scareOrigin), tickCount);
                        } else {
                            mode = FamiliarMode.IDLE;
                        }
                    } else {
                        mode = FamiliarMode.IDLE;
                    }
                }
            }
        }

        float lerpSpeed = (mode == FamiliarMode.SCARE || mode == FamiliarMode.POSSESSED || mode == FamiliarMode.DANCE) ? 0.65f : 0.34f;
        currentPose.lerpTowards(desired, lerpSpeed);

        if (canSendPacket) {
            boolean needsMoveFlags = !initializedFlags
                    || mode == FamiliarMode.FOLLOW
                    || mode == FamiliarMode.SCARE
                    || mode == FamiliarMode.POSSESSED
                    || mode == FamiliarMode.MEDITATE
                    || mode == FamiliarMode.FORMATION_CIRCLE
                    || mode == FamiliarMode.FORMATION_LINE
                    || mode == FamiliarMode.MARCH
                    || mode == FamiliarMode.STALK;
            PoserBridge.sendUpdate(stand, currentPose, moveDelta, needsMoveFlags);
            initializedFlags = true;
        }
    }

    private void tickEtherealSkull(ClientLevel level, LocalPlayer player, int index, int totalEthereal, int groupIndex, int groupTotal, long tickCount) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        float sideSign = (index % 2 == 0) ? 1.0f : -1.0f;
        int tier = index / 2;
        float angleDeg = player.getYRot() + sideSign * (58.0f + tier * 24.0f);
        float rad = angleDeg * Mth.DEG_TO_RAD;
        double shoulderDist = 0.85 + tier * 0.35;
        double bob = Math.sin((tickCount + index * 11) * 0.11) * 0.11;
        Vec3 shoulderPos = new Vec3(
                player.getX() - Mth.sin(rad) * shoulderDist,
                player.getY() + 1.48 + tier * 0.32 + bob,
                player.getZ() + Mth.cos(rad) * shoulderDist
        );

        if (currentSkullPos == null || currentSkullPos.distanceToSqr(player.position()) > 400.0) {
            currentSkullPos = shoulderPos;
        }

        Vec3 targetPos = shoulderPos;
        float targetYaw = player.getYRot() + Mth.sin((tickCount + index * 7) * 0.06f) * 14.0f;
        float targetPitch = player.getXRot() * 0.5f + Mth.cos(tickCount * 0.1f) * 6.0f;
        double moveLerp = 0.22;

        if (skullOverridePos != null) {
            targetPos = skullOverridePos.add(0, Math.sin(tickCount * 0.18) * 0.12, 0);
            targetYaw = (tickCount * 6.0f) % 360.0f;
            targetPitch = 15.0f;
        } else if (inspectTicksLeft > 0 && inspectFocusPos != null) {
            inspectTicksLeft--;
            if (inspectFocusPos.distanceToSqr(player.position()) <= 225.0) {
                targetPos = inspectFocusPos.add(0, 0.55 + Math.sin(tickCount * 0.15) * 0.12, 0);
            } else {
                Vec3 dir = inspectFocusPos.subtract(player.getEyePosition()).normalize();
                targetPos = player.getEyePosition().add(dir.scale(2.2));
            }
            targetYaw = yawBetween(currentSkullPos, inspectFocusPos);
            targetPitch = pitchBetween(currentSkullPos, inspectFocusPos);
            moveLerp = 0.12;
        } else if (mode == FamiliarMode.STALK) {
            Entity target = findEntityByUuid(level, stalkTargetUuid);
            if (target != null && target.isAlive() && target.distanceToSqr(player) <= 256.0) {
                targetPos = target.getEyePosition().subtract(target.getLookAngle().scale(1.4)).add(0, 0.55, 0);
                targetYaw = yawBetween(currentSkullPos, target.getEyePosition());
                targetPitch = 12.0f;
                moveLerp = 0.14;
            } else {
                mode = FamiliarMode.IDLE;
            }
        } else if (mode == FamiliarMode.DANCE) {
            double orbitAngle = (tickCount * 0.18) + index * 2.1;
            targetPos = player.position().add(Math.cos(orbitAngle) * 1.4, 1.85 + Math.sin(tickCount * 0.45) * 0.35, Math.sin(orbitAngle) * 1.4);
            targetYaw = (tickCount * 16.0f) % 360.0f;
            targetPitch = Mth.sin(tickCount * 0.55f) * 25.0f;
            if (tickCount % 6 == 0) {
                level.addParticle(ParticleTypes.NOTE, currentSkullPos.x, currentSkullPos.y + 0.4, currentSkullPos.z, rng.nextDouble(), 0.0, 0.0);
            }
        } else if (mode == FamiliarMode.FORMATION_CIRCLE) {
            double angle = (Math.PI * 2.0 * groupIndex) / Math.max(1, groupTotal);
            targetPos = new Vec3(player.getX() + Math.cos(angle) * 2.4, player.getY() + 1.65 + bob, player.getZ() + Math.sin(angle) * 2.4);
            targetYaw = yawBetween(player.position(), targetPos);
            targetPitch = 0.0f;
        } else if (mode == FamiliarMode.FORMATION_LINE) {
            float yawRad = player.getYRot() * Mth.DEG_TO_RAD;
            Vec3 forward = new Vec3(-Mth.sin(yawRad), 0, Mth.cos(yawRad));
            Vec3 right = new Vec3(Mth.cos(yawRad), 0, Mth.sin(yawRad));
            double offset = (groupIndex - (groupTotal - 1) * 0.5) * 1.25;
            targetPos = player.position().add(forward.scale(2.2)).add(right.scale(offset)).add(0, 1.6 + bob, 0);
            targetYaw = player.getYRot();
            targetPitch = 0.0f;
        } else if (mode == FamiliarMode.MARCH) {
            float yawRad = player.getYRot() * Mth.DEG_TO_RAD;
            Vec3 back = new Vec3(Mth.sin(yawRad), 0, -Mth.cos(yawRad));
            Vec3 right = new Vec3(Mth.cos(yawRad), 0, Mth.sin(yawRad));
            double side = (groupTotal == 1) ? 0.0 : ((groupIndex % 2 == 0) ? -0.7 : 0.7);
            targetPos = player.position().add(back.scale(1.6 + (groupIndex / 2) * 1.1)).add(right.scale(side)).add(0, 1.7 + Math.abs(Math.sin(tickCount * 0.4)) * 0.15, 0);
            targetYaw = player.getYRot();
        } else if (roamEnabled && mode != FamiliarMode.FOLLOW) {
            double distFromOwner = currentSkullPos.distanceTo(player.position());
            if (distFromOwner > 11.5 || roamGoal == null || roamGoal.distanceTo(player.position()) > 11.0) {
                roamGoal = WorldScout.pickInterestingRoamTarget(level, player, currentSkullPos);
                roamPauseTicks = 0;
            }

            double distToGoal = currentSkullPos.distanceTo(roamGoal);
            if (distToGoal < 0.45) {
                if (roamPauseTicks <= 0) {
                    roamPauseTicks = 35 + rng.nextInt(45);
                }
                roamPauseTicks--;
                if (roamPauseTicks <= 0) {
                    roamGoal = WorldScout.pickInterestingRoamTarget(level, player, currentSkullPos);
                }
                targetPos = roamGoal.add(0, Math.sin((tickCount + index * 13) * 0.12) * 0.14, 0);
                targetYaw = yawBetween(currentSkullPos, roamGoal) + Mth.sin(tickCount * 0.07f) * 45.0f;
                targetPitch = 14.0f + Mth.cos(tickCount * 0.09f) * 10.0f;
                moveLerp = 0.08;
            } else {
                targetPos = roamGoal;
                targetYaw = yawBetween(currentSkullPos, roamGoal);
                targetPitch = pitchBetween(currentSkullPos, roamGoal);
                moveLerp = 0.065;
            }
        }

        if (mode == FamiliarMode.BOW) {
            targetPitch = 42.0f;
            if (--actionTimer <= 0) mode = FamiliarMode.IDLE;
        } else if (mode == FamiliarMode.NOD) {
            targetPitch = Mth.sin(tickCount * 0.65f) * 28.0f;
            if (--actionTimer <= 0) mode = FamiliarMode.IDLE;
        } else if (mode == FamiliarMode.SHAKE_HEAD) {
            targetYaw += Mth.sin(tickCount * 0.65f) * 35.0f;
            if (--actionTimer <= 0) mode = FamiliarMode.IDLE;
        } else if (startleTicks > 0) {
            targetPos = targetPos.add((rng.nextDouble() - 0.5) * 0.15, 0.1, (rng.nextDouble() - 0.5) * 0.15);
        }

        currentSkullPos = currentSkullPos.lerp(targetPos, moveLerp);

        if (etherealVisual == null || !etherealVisual.isAlive() || etherealVisual.level() != level) {
            etherealVisual = new ArmorStand(level, currentSkullPos.x, currentSkullPos.y - 1.45, currentSkullPos.z);
            etherealVisual.setId(nextFakeEntityId--);
            etherealVisual.setInvisible(true);
            etherealVisual.setNoGravity(true);
            etherealVisual.setNoBasePlate(true);
            etherealVisual.setItemSlot(EquipmentSlot.HEAD, skullStack.copy());
            level.addEntity(etherealVisual);
        }

        Vec3 visualFeet = currentSkullPos.subtract(0, 1.45, 0);
        etherealVisual.setPos(visualFeet.x, visualFeet.y, visualFeet.z);
        etherealVisual.setYRot(targetYaw);
        etherealVisual.setYHeadRot(targetYaw);
        etherealVisual.setHeadPose(new Rotations(targetPitch, 0.0f, 0.0f));

        if (tickCount % 3 == 0) {
            double px = currentSkullPos.x + (rng.nextDouble() - 0.5) * 0.32;
            double py = currentSkullPos.y + (rng.nextDouble() - 0.5) * 0.25;
            double pz = currentSkullPos.z + (rng.nextDouble() - 0.5) * 0.32;
            level.addParticle(ParticleTypes.SOUL, px, py, pz, 0.0, 0.025, 0.0);
        }
        if (tickCount % 11 == 0) {
            level.addParticle(ParticleTypes.SOUL_FIRE_FLAME, currentSkullPos.x, currentSkullPos.y - 0.1, currentSkullPos.z, 0.0, 0.015, 0.0);
        }
    }

    public void dismiss(Minecraft client) {
        if (client.level != null && client.player != null) {
            Vec3 pos = currentSkullPos != null ? currentSkullPos : client.player.getEyePosition();
            ThreadLocalRandom rng = ThreadLocalRandom.current();
            for (int i = 0; i < 20; i++) {
                client.level.addParticle(ParticleTypes.SOUL, pos.x, pos.y, pos.z,
                        (rng.nextDouble() - 0.5) * 0.12,
                        0.06,
                        (rng.nextDouble() - 0.5) * 0.12);
            }
            client.level.playLocalSound(pos.x, pos.y, pos.z, SoundEvents.SOUL_ESCAPE.value(), SoundSource.PLAYERS, 0.8f, 0.6f, false);
        }
        discardEtherealVisual();
    }

    public void discardEtherealVisual() {
        if (etherealVisual != null) {
            etherealVisual.discard();
            etherealVisual = null;
        }
    }

    private static float yawBetween(Vec3 from, Vec3 to) {
        double dx = to.x - from.x;
        double dz = to.z - from.z;
        return (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90.0f;
    }

    private static float pitchBetween(Vec3 from, Vec3 to) {
        double dx = to.x - from.x;
        double dy = to.y - from.y;
        double dz = to.z - from.z;
        double horiz = Math.sqrt(dx * dx + dz * dz);
        return (float) -(Mth.atan2(dy, horiz) * Mth.RAD_TO_DEG);
    }

    private static boolean isPlayerLookingNear(Player player, Vec3 target, double dotThreshold) {
        Vec3 look = player.getLookAngle().normalize();
        Vec3 toTarget = target.subtract(player.getEyePosition()).normalize();
        return look.dot(toTarget) >= dotThreshold;
    }

    private static Entity findEntityByUuid(ClientLevel level, UUID uuid) {
        if (uuid == null || level == null) return null;
        for (Entity e : level.entitiesForRendering()) {
            if (e.getUUID().equals(uuid)) return e;
        }
        return null;
    }

    private static Entity findNearestThreat(ClientLevel level, Player owner, Vec3 origin, double radius) {
        List<Entity> candidates = level.getEntities(owner, owner.getBoundingBox().inflate(radius + 6.0), e ->
                e.isAlive() && e != owner && !(e instanceof ArmorStand) && (e instanceof Enemy || e instanceof Player)
        );
        return candidates.stream()
                .filter(e -> e.position().distanceToSqr(origin) <= radius * radius)
                .min(Comparator.comparingDouble(e -> e.position().distanceToSqr(origin)))
                .orElse(null);
    }
}
