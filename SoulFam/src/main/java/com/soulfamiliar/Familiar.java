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

public class Familiar {
    private static int nextFakeEntityId = -91000;

    private final String name;
    private final ItemStack skullStack;
    private ArmorStand etherealVisual;
    private UUID boundStandUuid;
    private FamiliarMode mode = FamiliarMode.IDLE;
    private final PoseState currentPose = new PoseState();
    private boolean initializedFlags = false;

    // Action state
    private int actionTimer = 0;
    private Vec3 pointTarget = null;
    private UUID scareTargetUuid = null;
    private Vec3 scareOrigin = null;
    private int scarePhase = 0; // 0 = dash, 1 = jumpscare, 2 = return
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

    public void setSkullOverridePos(Vec3 pos) {
        this.skullOverridePos = pos;
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

        // Burst of soul particles into the armor stand head
        Vec3 headPos = stand.position().add(0, 1.65, 0);
        for (int i = 0; i < 24; i++) {
            double ox = (client.level.random.nextDouble() - 0.5) * 0.7;
            double oy = (client.level.random.nextDouble() - 0.5) * 0.7;
            double oz = (client.level.random.nextDouble() - 0.5) * 0.7;
            client.level.addParticle(ParticleTypes.SOUL_FIRE_FLAME, headPos.x + ox, headPos.y + oy, headPos.z + oz, 0, 0.03, 0);
        }
        client.level.playLocalSound(headPos.x, headPos.y, headPos.z, SoundEvents.SOUL_ESCAPE, SoundSource.PLAYERS, 0.9f, 0.85f, false);
        discardEtherealVisual();
    }

    public void releaseStand(Minecraft client) {
        if (client.level != null && boundStandUuid != null) {
            ArmorStand stand = resolveBoundStand(client.level);
            if (stand != null) {
                Vec3 pos = stand.position().add(0, 1.65, 0);
                for (int i = 0; i < 16; i++) {
                    client.level.addParticle(ParticleTypes.SOUL, pos.x, pos.y, pos.z,
                            (client.level.random.nextDouble() - 0.5) * 0.08,
                            0.05,
                            (client.level.random.nextDouble() - 0.5) * 0.08);
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
        this.actionTimer = 55; // ~2.75 seconds
    }

    public void triggerPoint(Vec3 target) {
        this.mode = FamiliarMode.POINT;
        this.pointTarget = target;
        this.actionTimer = 70;
    }

    public void triggerScare(Player targetPlayer, ArmorStand currentStand) {
        if (targetPlayer == null) return;
        this.mode = FamiliarMode.SCARE;
        this.scareTargetUuid = targetPlayer.getUUID();
        this.scareOrigin = currentStand != null ? currentStand.position() : null;
        this.scarePhase = 0;
        this.actionTimer = 0;
    }

    public void tick(Minecraft client, int index, int totalEthereal, long tickCount, boolean canSendPacket) {
        ClientLevel level = client.level;
        LocalPlayer player = client.player;
        if (level == null || player == null) return;

        // Track player damage for startle reaction
        float hp = player.getHealth();
        if (lastPlayerHealth > 0 && hp < lastPlayerHealth) {
            startleTicks = 25;
        }
        lastPlayerHealth = hp;
        if (startleTicks > 0) startleTicks--;

        ArmorStand stand = resolveBoundStand(level);

        // If unbound, render and animate the floating skull near player's shoulder
        if (stand == null) {
            tickEtherealSkull(level, player, index, totalEthereal, tickCount);
            return;
        } else {
            discardEtherealVisual();
        }

        if (mode == FamiliarMode.FROZEN) {
            return;
        }

        // Check range limit (~12 blocks)
        if (player.distanceToSqr(stand) > 144.0) {
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

                // Move stand if player presses movement keys while possessing
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
                    // Auto-bow if owner comes within 4 blocks and looks near the familiar
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
                float yaw = yawBetween(standPos, target);
                desired = PoseState.bow(yaw);
                if (--actionTimer <= 0) {
                    mode = FamiliarMode.IDLE;
                }
            }
            case POINT -> {
                Vec3 target = pointTarget != null ? pointTarget : player.position();
                float yaw = yawBetween(standPos, target);
                float pitch = pitchBetween(standPos.add(0, 1.5, 0), target);
                desired = PoseState.point(yaw, pitch);
                if (--actionTimer <= 0) {
                    mode = FamiliarMode.IDLE;
                }
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
                float rad = (player.getYRot() + 55.0f + index * 30.0f) * Mth.DEG_TO_RAD;
                Vec3 desiredPos = player.position().add(-Mth.sin(rad) * 1.4, 0.55 + Math.sin(tickCount * 0.1) * 0.12, Mth.cos(rad) * 1.4);
                Vec3 diff = desiredPos.subtract(standPos);
                if (diff.length() > 0.15) {
                    moveDelta = diff.scale(0.35);
                }
                desired = PoseState.idle(player.getYRot(), tickCount);
                desired.leftLegX = 14.0f;
                desired.rightLegX = 22.0f;
            }
            case SCARE -> {
                Player victim = scareTargetUuid != null ? level.getPlayerByUUID(scareTargetUuid) : null;
                if (victim == null) {
                    mode = FamiliarMode.IDLE;
                    break;
                }
                if (scarePhase == 0) {
                    // Phase 0: Dash rapidly right in front of victim's face
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
                        actionTimer = 32; // Hold jumpscare pose ~1.6s
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
                    // Phase 2: Glide back to original spot
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

        float lerpSpeed = (mode == FamiliarMode.SCARE || mode == FamiliarMode.POSSESSED) ? 0.65f : 0.32f;
        currentPose.lerpTowards(desired, lerpSpeed);

        if (canSendPacket) {
            PoserBridge.sendUpdate(stand, currentPose, moveDelta, !initializedFlags || mode == FamiliarMode.FOLLOW || mode == FamiliarMode.SCARE || mode == FamiliarMode.POSSESSED);
            initializedFlags = true;
        }
    }

    private void tickEtherealSkull(ClientLevel level, LocalPlayer player, int index, int totalEthereal, long tickCount) {
        Vec3 targetPos;
        float targetYaw;
        float targetPitch;

        if (skullOverridePos != null) {
            targetPos = skullOverridePos.add(0, Math.sin(tickCount * 0.18) * 0.12, 0);
            targetYaw = (tickCount * 6.0f) % 360.0f;
            targetPitch = 15.0f;
        } else {
            // Distribute multiple skulls around the player's shoulders
            float sideSign = (index % 2 == 0) ? 1.0f : -1.0f;
            int tier = index / 2;
            float angleDeg = player.getYRot() + sideSign * (58.0f + tier * 24.0f);
            float rad = angleDeg * Mth.DEG_TO_RAD;
            double dist = 0.85 + tier * 0.35;
            double bob = Math.sin((tickCount + index * 11) * 0.11) * 0.11;

            targetPos = new Vec3(
                    player.getX() - Mth.sin(rad) * dist,
                    player.getY() + 1.48 + tier * 0.32 + bob,
                    player.getZ() + Mth.cos(rad) * dist
            );
            targetYaw = player.getYRot() + Mth.sin((tickCount + index * 7) * 0.06f) * 14.0f;
            targetPitch = player.getXRot() * 0.5f + Mth.cos(tickCount * 0.1f) * 6.0f;

            if (mode == FamiliarMode.BOW) {
                targetPitch = 42.0f;
                if (--actionTimer <= 0) mode = FamiliarMode.IDLE;
            } else if (startleTicks > 0) {
                targetPos = targetPos.add((level.random.nextDouble() - 0.5) * 0.15, 0.1, (level.random.nextDouble() - 0.5) * 0.15);
            }
        }

        if (etherealVisual == null || !etherealVisual.isAlive() || etherealVisual.level() != level) {
            etherealVisual = new ArmorStand(level, targetPos.x, targetPos.y - 1.45, targetPos.z);
            etherealVisual.setId(nextFakeEntityId--);
            etherealVisual.setInvisible(true);
            etherealVisual.setNoGravity(true);
            etherealVisual.setNoBasePlate(true);
            etherealVisual.setMarker(true);
            etherealVisual.setItemSlot(EquipmentSlot.HEAD, skullStack.copy());
            level.addEntity(etherealVisual);
        }

        // ArmorStand head sits ~1.45 blocks above its feet position
        Vec3 visualFeet = targetPos.subtract(0, 1.45, 0);
        etherealVisual.setPos(visualFeet.x, visualFeet.y, visualFeet.z);
        etherealVisual.setYRot(targetYaw);
        etherealVisual.setYHeadRot(targetYaw);
        etherealVisual.setHeadPose(new Rotations(targetPitch, 0.0f, 0.0f));

        // Client-only soul particles trailing from the skull
        if (tickCount % 3 == 0) {
            double px = targetPos.x + (level.random.nextDouble() - 0.5) * 0.32;
            double py = targetPos.y + (level.random.nextDouble() - 0.5) * 0.25;
            double pz = targetPos.z + (level.random.nextDouble() - 0.5) * 0.32;
            level.addParticle(ParticleTypes.SOUL, px, py, pz, 0.0, 0.025, 0.0);
        }
        if (tickCount % 11 == 0) {
            level.addParticle(ParticleTypes.SOUL_FIRE_FLAME, targetPos.x, targetPos.y - 0.1, targetPos.z, 0.0, 0.015, 0.0);
        }
    }

    public void dismiss(Minecraft client) {
        if (client.level != null && client.player != null) {
            Vec3 pos = etherealVisual != null ? etherealVisual.position().add(0, 1.45, 0) : client.player.getEyePosition();
            for (int i = 0; i < 20; i++) {
                client.level.addParticle(ParticleTypes.SOUL, pos.x, pos.y, pos.z,
                        (client.level.random.nextDouble() - 0.5) * 0.12,
                        0.06,
                        (client.level.random.nextDouble() - 0.5) * 0.12);
            }
            client.level.playLocalSound(pos.x, pos.y, pos.z, SoundEvents.SOUL_ESCAPE, SoundSource.PLAYERS, 0.8f, 0.6f, false);
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
