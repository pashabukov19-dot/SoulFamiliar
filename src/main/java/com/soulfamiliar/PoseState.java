package com.soulfamiliar;

import net.minecraft.util.Mth;

public class PoseState {
    public float bodyYaw;
    public float headX, headY, headZ;
    public float bodyX, bodyY, bodyZ;
    public float leftArmX, leftArmY, leftArmZ;
    public float rightArmX, rightArmY, rightArmZ;
    public float leftLegX, leftLegY, leftLegZ;
    public float rightLegX, rightLegY, rightLegZ;

    public PoseState() {}

    public PoseState copy() {
        PoseState p = new PoseState();
        p.bodyYaw = this.bodyYaw;
        p.headX = this.headX; p.headY = this.headY; p.headZ = this.headZ;
        p.bodyX = this.bodyX; p.bodyY = this.bodyY; p.bodyZ = this.bodyZ;
        p.leftArmX = this.leftArmX; p.leftArmY = this.leftArmY; p.leftArmZ = this.leftArmZ;
        p.rightArmX = this.rightArmX; p.rightArmY = this.rightArmY; p.rightArmZ = this.rightArmZ;
        p.leftLegX = this.leftLegX; p.leftLegY = this.leftLegY; p.leftLegZ = this.leftLegZ;
        p.rightLegX = this.rightLegX; p.rightLegY = this.rightLegY; p.rightLegZ = this.rightLegZ;
        return p;
    }

    public void lerpTowards(PoseState target, float t) {
        this.bodyYaw = rotLerp(t, this.bodyYaw, target.bodyYaw);
        this.headX = Mth.lerp(t, this.headX, target.headX);
        this.headY = Mth.lerp(t, this.headY, target.headY);
        this.headZ = Mth.lerp(t, this.headZ, target.headZ);
        this.bodyX = Mth.lerp(t, this.bodyX, target.bodyX);
        this.bodyY = Mth.lerp(t, this.bodyY, target.bodyY);
        this.bodyZ = Mth.lerp(t, this.bodyZ, target.bodyZ);
        this.leftArmX = Mth.lerp(t, this.leftArmX, target.leftArmX);
        this.leftArmY = Mth.lerp(t, this.leftArmY, target.leftArmY);
        this.leftArmZ = Mth.lerp(t, this.leftArmZ, target.leftArmZ);
        this.rightArmX = Mth.lerp(t, this.rightArmX, target.rightArmX);
        this.rightArmY = Mth.lerp(t, this.rightArmY, target.rightArmY);
        this.rightArmZ = Mth.lerp(t, this.rightArmZ, target.rightArmZ);
        this.leftLegX = Mth.lerp(t, this.leftLegX, target.leftLegX);
        this.leftLegY = Mth.lerp(t, this.leftLegY, target.leftLegY);
        this.leftLegZ = Mth.lerp(t, this.leftLegZ, target.leftLegZ);
        this.rightLegX = Mth.lerp(t, this.rightLegX, target.rightLegX);
        this.rightLegY = Mth.lerp(t, this.rightLegY, target.rightLegY);
        this.rightLegZ = Mth.lerp(t, this.rightLegZ, target.rightLegZ);
    }

    private static float rotLerp(float delta, float start, float end) {
        float diff = Mth.wrapDegrees(end - start);
        return start + delta * diff;
    }

    public static PoseState idle(float yaw, float time) {
        PoseState p = new PoseState();
        p.bodyYaw = yaw;
        float breath = Mth.sin(time * 0.12f);
        p.headX = 2.0f + breath * 1.5f;
        p.headY = Mth.sin(time * 0.04f) * 8.0f;
        p.bodyX = breath * 1.0f;
        p.leftArmX = -4.0f + breath * 2.0f;
        p.leftArmZ = -6.0f - breath * 1.5f;
        p.rightArmX = -4.0f - breath * 2.0f;
        p.rightArmZ = 6.0f + breath * 1.5f;
        return p;
    }

    public static PoseState bow(float yaw) {
        PoseState p = new PoseState();
        p.bodyYaw = yaw;
        p.headX = 38.0f;
        p.bodyX = 32.0f;
        p.leftArmX = -15.0f;
        p.leftArmZ = -12.0f;
        p.rightArmX = -55.0f;
        p.rightArmY = -25.0f;
        p.rightArmZ = 10.0f;
        p.leftLegX = -4.0f;
        p.rightLegX = 4.0f;
        return p;
    }

    public static PoseState guard(float yaw, float headPitch, float headYawOffset, float time) {
        PoseState p = new PoseState();
        p.bodyYaw = yaw;
        p.headX = Mth.clamp(headPitch, -45.0f, 45.0f);
        p.headY = Mth.clamp(headYawOffset, -55.0f, 55.0f);
        p.bodyX = 4.0f;
        float sway = Mth.sin(time * 0.18f) * 2.5f;
        p.leftArmX = -42.0f + sway;
        p.leftArmY = 18.0f;
        p.leftArmZ = -14.0f;
        p.rightArmX = -58.0f - sway;
        p.rightArmY = -16.0f;
        p.rightArmZ = 14.0f;
        p.leftLegX = -8.0f;
        p.leftLegZ = -4.0f;
        p.rightLegX = 8.0f;
        p.rightLegZ = 4.0f;
        return p;
    }

    public static PoseState point(float yaw, float pitch) {
        PoseState p = new PoseState();
        p.bodyYaw = yaw;
        p.headX = Mth.clamp(pitch, -50.0f, 50.0f);
        p.bodyX = 3.0f;
        p.rightArmX = -92.0f + Mth.clamp(pitch, -45.0f, 45.0f);
        p.rightArmY = -6.0f;
        p.rightArmZ = 4.0f;
        p.leftArmX = -6.0f;
        p.leftArmZ = -8.0f;
        return p;
    }

    public static PoseState sprint(float yaw, float time) {
        PoseState p = new PoseState();
        p.bodyYaw = yaw;
        float swing = Mth.sin(time * 1.15f) * 52.0f;
        p.headX = -10.0f;
        p.bodyX = 16.0f;
        p.leftArmX = swing;
        p.rightArmX = -swing;
        p.leftLegX = -swing * 0.9f;
        p.rightLegX = swing * 0.9f;
        return p;
    }

    public static PoseState march(float yaw, float time, boolean moving) {
        PoseState p = new PoseState();
        p.bodyYaw = yaw;
        p.headX = -4.0f;
        p.bodyX = 2.0f;
        if (moving) {
            float swing = Mth.sin(time * 0.65f) * 62.0f;
            p.leftArmX = swing;
            p.rightArmX = -swing;
            p.leftLegX = -swing * 0.85f;
            p.rightLegX = swing * 0.85f;
        } else {
            p.leftArmZ = -3.0f;
            p.rightArmZ = 3.0f;
        }
        return p;
    }

    public static PoseState jumpscare(float yaw) {
        PoseState p = new PoseState();
        p.bodyYaw = yaw;
        p.headX = -18.0f;
        p.headZ = 28.0f;
        p.bodyX = 24.0f;
        p.leftArmX = -145.0f;
        p.leftArmZ = -26.0f;
        p.rightArmX = -145.0f;
        p.rightArmZ = 26.0f;
        p.leftLegX = -12.0f;
        p.rightLegX = 12.0f;
        return p;
    }

    public static PoseState fallen(float yaw) {
        PoseState p = new PoseState();
        p.bodyYaw = yaw;
        p.headX = 20.0f;
        p.headZ = 35.0f;
        p.bodyX = 82.0f;
        p.leftArmX = -25.0f;
        p.leftArmZ = -40.0f;
        p.rightArmX = -15.0f;
        p.rightArmZ = 45.0f;
        p.leftLegX = 80.0f;
        p.rightLegX = 84.0f;
        return p;
    }

    public static PoseState kneel(float yaw) {
        PoseState p = new PoseState();
        p.bodyYaw = yaw;
        p.headX = 26.0f;
        p.bodyX = 18.0f;
        p.rightArmX = -52.0f;
        p.rightArmY = -36.0f;
        p.rightArmZ = 8.0f;
        p.leftArmX = -24.0f;
        p.leftArmZ = -10.0f;
        p.leftLegX = -75.0f;
        p.rightLegX = 65.0f;
        return p;
    }

    public static PoseState meditate(float yaw, float time) {
        PoseState p = new PoseState();
        p.bodyYaw = yaw;
        float sway = Mth.sin(time * 0.08f) * 2.0f;
        p.headX = 8.0f + sway;
        p.bodyX = -2.0f;
        p.leftArmX = -38.0f;
        p.leftArmY = 25.0f;
        p.leftArmZ = -28.0f;
        p.rightArmX = -38.0f;
        p.rightArmY = -25.0f;
        p.rightArmZ = 28.0f;
        p.leftLegX = -78.0f;
        p.leftLegY = -38.0f;
        p.leftLegZ = -15.0f;
        p.rightLegX = -78.0f;
        p.rightLegY = 38.0f;
        p.rightLegZ = 15.0f;
        return p;
    }

    public static PoseState applaud(float yaw, float time) {
        PoseState p = new PoseState();
        p.bodyYaw = yaw;
        float clap = Math.abs(Mth.sin(time * 0.55f));
        p.headX = -4.0f + clap * 4.0f;
        p.bodyX = 2.0f;
        p.leftArmX = -68.0f;
        p.leftArmY = 28.0f - clap * 18.0f;
        p.leftArmZ = -10.0f;
        p.rightArmX = -68.0f;
        p.rightArmY = -28.0f + clap * 18.0f;
        p.rightArmZ = 10.0f;
        return p;
    }

    public static PoseState threaten(float yaw, float time) {
        PoseState p = new PoseState();
        p.bodyYaw = yaw;
        float pulse = Mth.sin(time * 0.25f) * 4.0f;
        p.headX = -8.0f;
        p.headZ = -6.0f;
        p.bodyX = 8.0f;
        p.bodyY = -12.0f;
        p.rightArmX = -155.0f + pulse;
        p.rightArmY = -18.0f;
        p.rightArmZ = 16.0f;
        p.leftArmX = -48.0f;
        p.leftArmY = 32.0f;
        p.leftArmZ = -18.0f;
        p.leftLegX = -16.0f;
        p.rightLegX = 18.0f;
        return p;
    }

    public static PoseState think(float yaw, float time) {
        PoseState p = new PoseState();
        p.bodyYaw = yaw;
        p.headX = 14.0f;
        p.headY = Mth.sin(time * 0.05f) * 10.0f;
        p.headZ = 12.0f;
        p.bodyX = 3.0f;
        p.leftArmX = -45.0f;
        p.leftArmY = 48.0f;
        p.leftArmZ = -10.0f;
        p.rightArmX = -112.0f;
        p.rightArmY = -32.0f;
        p.rightArmZ = 12.0f;
        return p;
    }

    public static PoseState drama(float yaw, float time) {
        PoseState p = new PoseState();
        p.bodyYaw = yaw;
        float shake = Mth.sin(time * 0.4f) * 5.0f;
        p.headX = 22.0f;
        p.headZ = shake;
        p.bodyX = -12.0f;
        p.leftArmX = -126.0f;
        p.leftArmY = 26.0f;
        p.leftArmZ = -8.0f;
        p.rightArmX = -126.0f;
        p.rightArmY = -26.0f;
        p.rightArmZ = 8.0f;
        p.leftLegX = -10.0f;
        p.rightLegX = 12.0f;
        return p;
    }

    public static PoseState nod(float yaw, float time) {
        PoseState p = idle(yaw, time);
        p.headX = Mth.sin(time * 0.65f) * 26.0f;
        p.headY = 0.0f;
        return p;
    }

    public static PoseState shakeHead(float yaw, float time) {
        PoseState p = idle(yaw, time);
        p.headX = 4.0f;
        p.headY = Mth.sin(time * 0.65f) * 34.0f;
        return p;
    }

    public static PoseState dance(float baseYaw, float time) {
        PoseState p = new PoseState();
        int phase = ((int) (time / 28.0f)) % 4;
        float beat = Mth.sin(time * 0.55f);
        float fastBeat = Mth.sin(time * 1.1f);

        switch (phase) {
            case 0 -> {
                p.bodyYaw = baseYaw + beat * 16.0f;
                p.headX = -8.0f + fastBeat * 8.0f;
                p.headZ = beat * 12.0f;
                p.bodyX = -4.0f + fastBeat * 4.0f;
                p.rightArmX = -148.0f + fastBeat * 14.0f;
                p.rightArmZ = 22.0f;
                p.leftArmX = -25.0f;
                p.leftArmY = 40.0f;
                p.leftArmZ = -28.0f;
                p.leftLegX = beat * 18.0f;
                p.rightLegX = -beat * 18.0f;
            }
            case 1 -> {
                p.bodyYaw = baseYaw + beat * 22.0f;
                p.headY = -beat * 25.0f;
                p.bodyZ = beat * 10.0f;
                p.leftArmX = -90.0f + Mth.sin(time * 0.55f) * 45.0f;
                p.leftArmZ = -35.0f;
                p.rightArmX = -90.0f + Mth.sin(time * 0.55f + 1.5f) * 45.0f;
                p.rightArmZ = 35.0f;
                p.leftLegZ = -10.0f;
                p.rightLegZ = 10.0f;
            }
            case 2 -> {
                p.bodyYaw = (baseYaw + (time * 14.0f)) % 360.0f;
                p.headX = -6.0f;
                p.leftArmX = -85.0f;
                p.leftArmZ = -42.0f;
                p.rightArmX = -85.0f;
                p.rightArmZ = 42.0f;
                p.leftLegX = 14.0f;
                p.rightLegX = -14.0f;
            }
            default -> {
                p.bodyYaw = baseYaw;
                p.headX = fastBeat * 10.0f;
                p.bodyX = 10.0f;
                p.leftArmX = -55.0f;
                p.leftArmY = 35.0f;
                p.rightArmX = -55.0f;
                p.rightArmY = -35.0f;
                p.leftLegX = Math.max(0.0f, beat) * -75.0f;
                p.rightLegX = Math.max(0.0f, -beat) * -75.0f;
            }
        }
        return p;
    }
}
