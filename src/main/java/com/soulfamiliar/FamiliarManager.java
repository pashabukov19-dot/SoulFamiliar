package com.soulfamiliar;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class FamiliarManager {
    private static final FamiliarManager INSTANCE = new FamiliarManager();

    private final Map<String, Familiar> familiars = new LinkedHashMap<>();
    private final ChessBoardManager chessBoard = new ChessBoardManager();
    private long tickCounter = 0;
    private int roundRobinIndex = 0;
    private String possessingFamiliarKey = null;

    public static FamiliarManager getInstance() {
        return INSTANCE;
    }

    public ChessBoardManager getChessBoard() {
        return chessBoard;
    }

    public Collection<Familiar> getAll() {
        return familiars.values();
    }

    public Familiar getByName(String name) {
        if (name == null) return null;
        return familiars.get(name.trim().toLowerCase(Locale.ROOT));
    }

    public void summonFamiliar(Minecraft client, String rawName, boolean requireSkullInHand) {
        if (client.player == null || client.level == null) return;
        String cleanName = rawName.trim();
        if (cleanName.isEmpty()) {
            notifyPlayer(client, "Укажи имя фамильяра: Восстань [Имя]", ChatFormatting.RED);
            return;
        }

        ItemStack catalyst = findCatalystSkull(client.player);
        if (catalyst.isEmpty()) {
            if (requireSkullInHand) {
                notifyPlayer(client, "Возьми в руку череп скелета, череп визер-скелета или голову игрока для ритуала.", ChatFormatting.GRAY);
                return;
            }
            catalyst = new ItemStack(Items.SKELETON_SKULL);
        }

        String key = cleanName.toLowerCase(Locale.ROOT);
        Familiar existing = familiars.get(key);
        if (existing != null) {
            existing.dismiss(client);
        }

        Familiar familiar = new Familiar(cleanName, catalyst);
        familiars.put(key, familiar);

        client.level.playLocalSound(
                client.player.getX(), client.player.getY(), client.player.getZ(),
                SoundEvents.SOUL_ESCAPE.value(), SoundSource.PLAYERS, 1.0f, 0.75f, false
        );
        notifyPlayer(client, "Дух «" + cleanName + "» откликнулся на зов и парит у твоего плеча.", ChatFormatting.AQUA);
    }

    public void possessTargetStand(Minecraft client, String name) {
        if (client.player == null || client.level == null) return;
        Familiar f = getByName(name);
        if (f == null) {
            notifyPlayer(client, "Фамильяр с именем «" + name + "» не призван.", ChatFormatting.RED);
            return;
        }
        ArmorStand target = raycastArmorStand(client, 10.0);
        if (target == null) {
            notifyPlayer(client, "Наведи прицел на стойку для брони в пределах 10 блоков.", ChatFormatting.YELLOW);
            return;
        }
        f.bindToStand(client, target);
        notifyPlayer(client, "«" + f.getName() + "» вселился в стойку для брони.", ChatFormatting.AQUA);
    }

    public void releaseFamiliar(Minecraft client, String name) {
        Familiar f = getByName(name);
        if (f == null) return;
        if (possessingFamiliarKey != null && possessingFamiliarKey.equalsIgnoreCase(f.getName())) {
            stopCameraPossession(client);
        }
        f.releaseStand(client);
        notifyPlayer(client, "«" + f.getName() + "» покинул стойку и вернулся к твоему плечу.", ChatFormatting.AQUA);
    }

    public void dismissFamiliar(Minecraft client, String name) {
        String key = name.trim().toLowerCase(Locale.ROOT);
        if (possessingFamiliarKey != null && possessingFamiliarKey.equals(key)) {
            stopCameraPossession(client);
        }
        Familiar removed = familiars.remove(key);
        if (removed != null) {
            removed.dismiss(client);
            notifyPlayer(client, "«" + removed.getName() + "» развеялся в потоке душ.", ChatFormatting.GRAY);
        }
    }

    public void startCameraPossession(Minecraft client, String name) {
        if (client.player == null || client.level == null) return;
        Familiar f = getByName(name);
        if (f == null) {
            notifyPlayer(client, "Фамильяр «" + name + "» не найден.", ChatFormatting.RED);
            return;
        }
        ArmorStand stand = f.resolveBoundStand(client.level);
        if (stand == null) {
            notifyPlayer(client, "«" + f.getName() + "» сейчас в форме черепа — сначала всели его в стойку.", ChatFormatting.YELLOW);
            return;
        }
        if (possessingFamiliarKey != null) {
            Familiar prev = familiars.get(possessingFamiliarKey);
            if (prev != null && prev.getMode() == FamiliarMode.POSSESSED) {
                prev.setMode(FamiliarMode.IDLE);
            }
        }
        possessingFamiliarKey = f.getName().toLowerCase(Locale.ROOT);
        f.setMode(FamiliarMode.POSSESSED);
        client.setCameraEntity(stand);
        notifyPlayer(client, "Ты смотришь глазами «" + f.getName() + "». Нажми [V] или напиши «Вернись», чтобы выйти.", ChatFormatting.LIGHT_PURPLE);
    }

    public void stopCameraPossession(Minecraft client) {
        if (client.player != null) {
            client.setCameraEntity(client.player);
        }
        if (possessingFamiliarKey != null) {
            Familiar f = familiars.get(possessingFamiliarKey);
            if (f != null && f.getMode() == FamiliarMode.POSSESSED) {
                f.setMode(FamiliarMode.IDLE);
            }
            possessingFamiliarKey = null;
            notifyPlayer(client, "Зрение вернулось в твоё тело.", ChatFormatting.GRAY);
        }
    }

    public void toggleNextPossessionView(Minecraft client) {
        if (possessingFamiliarKey != null) {
            stopCameraPossession(client);
            return;
        }
        if (client.level == null) return;
        for (Familiar f : familiars.values()) {
            if (f.resolveBoundStand(client.level) != null) {
                startCameraPossession(client, f.getName());
                return;
            }
        }
        notifyPlayer(client, "Нет ни одного фамильяра, вселённого в стойку.", ChatFormatting.YELLOW);
    }

    public void tick(Minecraft client) {
        tickCounter++;
        if (client.level == null || client.player == null) {
            for (Familiar f : familiars.values()) {
                f.discardEtherealVisual();
            }
            possessingFamiliarKey = null;
            return;
        }

        // Validate camera possession state
        if (possessingFamiliarKey != null) {
            Familiar active = familiars.get(possessingFamiliarKey);
            ArmorStand stand = active != null ? active.resolveBoundStand(client.level) : null;
            if (stand == null || !stand.isAlive() || client.player.distanceToSqr(stand) > 169.0) {
                stopCameraPossession(client);
            } else if (client.getCameraEntity() != stand) {
                client.setCameraEntity(stand);
            }
        }

        // Tick chess animations first (uses packet budget when moving a piece)
        boolean chessUsedPacket = chessBoard.tick(client, tickCounter);

        List<Familiar> list = new ArrayList<>(familiars.values());
        if (list.isEmpty()) return;

        int totalEthereal = 0;
        for (Familiar f : list) {
            if (f.resolveBoundStand(client.level) == null) totalEthereal++;
        }

        // Rate-limit ArmorPoser packets to max 1 packet every 2 ticks (10 pkt/s total)
        boolean packetWindow = !chessUsedPacket && (tickCounter % 2 == 0);
        int chosenForPacket = -1;
        if (packetWindow) {
            roundRobinIndex = (roundRobinIndex + 1) % list.size();
            chosenForPacket = roundRobinIndex;
        }

        int etherealIdx = 0;
        for (int i = 0; i < list.size(); i++) {
            Familiar f = list.get(i);
            boolean isEthereal = f.resolveBoundStand(client.level) == null;
            int idxForSlot = isEthereal ? etherealIdx++ : 0;
            boolean allowSend = packetWindow && (i == chosenForPacket);
            f.tick(client, idxForSlot, totalEthereal, tickCounter, allowSend);
        }
    }

    private static ItemStack findCatalystSkull(Player player) {
        ItemStack main = player.getMainHandItem();
        if (isSkullItem(main)) return main;
        ItemStack off = player.getOffhandItem();
        if (isSkullItem(off)) return off;
        return ItemStack.EMPTY;
    }

    private static boolean isSkullItem(ItemStack stack) {
        if (stack.isEmpty()) return false;
        return stack.is(Items.SKELETON_SKULL)
                || stack.is(Items.WITHER_SKELETON_SKULL)
                || stack.is(Items.PLAYER_HEAD)
                || stack.is(Items.ZOMBIE_HEAD)
                || stack.is(Items.CREEPER_HEAD)
                || stack.is(Items.PIGLIN_HEAD)
                || stack.is(Items.DRAGON_HEAD);
    }

    public static ArmorStand raycastArmorStand(Minecraft client, double range) {
        if (client.player == null || client.level == null) return null;
        Vec3 eye = client.player.getEyePosition();
        Vec3 look = client.player.getLookAngle();
        Vec3 end = eye.add(look.scale(range));
        AABB box = client.player.getBoundingBox().expandTowards(look.scale(range)).inflate(1.0);
        EntityHitResult hit = ProjectileUtil.getEntityHitResult(
                client.player, eye, end, box,
                e -> e instanceof ArmorStand && e.isAlive(),
                range * range
        );
        return hit != null && hit.getEntity() instanceof ArmorStand stand ? stand : null;
    }

    public static Player raycastPlayer(Minecraft client, double range) {
        if (client.player == null || client.level == null) return null;
        Vec3 eye = client.player.getEyePosition();
        Vec3 look = client.player.getLookAngle();
        Vec3 end = eye.add(look.scale(range));
        AABB box = client.player.getBoundingBox().expandTowards(look.scale(range)).inflate(1.0);
        EntityHitResult hit = ProjectileUtil.getEntityHitResult(
                client.player, eye, end, box,
                e -> e instanceof Player && e != client.player && e.isAlive(),
                range * range
        );
        return hit != null && hit.getEntity() instanceof Player p ? p : null;
    }

    public static Vec3 raycastLookPoint(Minecraft client, double range) {
        if (client.player == null || client.level == null) return Vec3.ZERO;
        Vec3 eye = client.player.getEyePosition();
        Vec3 look = client.player.getLookAngle();
        Vec3 end = eye.add(look.scale(range));

        EntityHitResult entityHit = ProjectileUtil.getEntityHitResult(
                client.player, eye, end,
                client.player.getBoundingBox().expandTowards(look.scale(range)).inflate(1.0),
                e -> e != client.player && e.isAlive(),
                range * range
        );
        if (entityHit != null) {
            return entityHit.getLocation();
        }
        BlockHitResult blockHit = client.level.clip(new ClipContext(
                eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, client.player
        ));
        if (blockHit.getType() != HitResult.Type.MISS) {
            return blockHit.getLocation();
        }
        return end;
    }

    public static void notifyPlayer(Minecraft client, String text, ChatFormatting color) {
        if (client.player != null) {
            client.player.sendSystemMessage(
                    Component.literal("[Фамильяр] ").withStyle(ChatFormatting.DARK_AQUA)
                            .append(Component.literal(text).withStyle(color))
            );
        }
    }
}
