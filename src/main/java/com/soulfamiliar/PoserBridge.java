package com.soulfamiliar;

import com.mrbysco.armorposer.platform.Services;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.FloatTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.phys.Vec3;

public final class PoserBridge {
    private PoserBridge() {}

    public static void sendUpdate(ArmorStand stand, PoseState pose, Vec3 moveDelta, boolean ensureBaseFlags) {
        if (stand == null || !stand.isAlive()) return;

        CompoundTag tag = new CompoundTag();

        if (ensureBaseFlags) {
            tag.putBoolean("ShowArms", true);
            tag.putBoolean("NoBasePlate", true);
            tag.putBoolean("NoGravity", true);
        }

        ListTag rotList = new ListTag();
        rotList.add(FloatTag.valueOf(pose.bodyYaw));
        rotList.add(FloatTag.valueOf(0.0f));
        tag.put("Rotation", rotList);

        CompoundTag poseTag = new CompoundTag();
        poseTag.put("Head", floatList(pose.headX, pose.headY, pose.headZ));
        poseTag.put("Body", floatList(pose.bodyX, pose.bodyY, pose.bodyZ));
        poseTag.put("LeftArm", floatList(pose.leftArmX, pose.leftArmY, pose.leftArmZ));
        poseTag.put("RightArm", floatList(pose.rightArmX, pose.rightArmY, pose.rightArmZ));
        poseTag.put("LeftLeg", floatList(pose.leftLegX, pose.leftLegY, pose.leftLegZ));
        poseTag.put("RightLeg", floatList(pose.rightLegX, pose.rightLegY, pose.rightLegZ));
        tag.put("Pose", poseTag);

        if (moveDelta != null && moveDelta.lengthSqr() > 1.0E-6) {
            double dx = clampMove(moveDelta.x);
            double dy = clampMove(moveDelta.y);
            double dz = clampMove(moveDelta.z);
            tag.store("Move", Vec3.CODEC, new Vec3(dx, dy, dz));
        }

        Services.PLATFORM.updateEntity(stand, tag);
    }

    public static void sendMoveOnly(ArmorStand stand, Vec3 moveDelta, Float yaw) {
        if (stand == null || !stand.isAlive()) return;
        CompoundTag tag = new CompoundTag();
        tag.putBoolean("NoGravity", true);
        tag.putBoolean("NoBasePlate", true);
        if (yaw != null) {
            ListTag rotList = new ListTag();
            rotList.add(FloatTag.valueOf(yaw));
            rotList.add(FloatTag.valueOf(0.0f));
            tag.put("Rotation", rotList);
        }
        if (moveDelta != null && moveDelta.lengthSqr() > 1.0E-6) {
            tag.store("Move", Vec3.CODEC, new Vec3(
                    clampMove(moveDelta.x),
                    clampMove(moveDelta.y),
                    clampMove(moveDelta.z)
            ));
        }
        Services.PLATFORM.updateEntity(stand, tag);
    }

    private static double clampMove(double v) {
        return Math.max(-7.5, Math.min(7.5, v));
    }

    private static ListTag floatList(float x, float y, float z) {
        ListTag list = new ListTag();
        list.add(FloatTag.valueOf(x));
        list.add(FloatTag.valueOf(y));
        list.add(FloatTag.valueOf(z));
        return list;
    }
}
