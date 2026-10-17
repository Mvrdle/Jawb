package com.example.jawbus.mixin;

import com.example.jawbus.Cinematic;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Visual-only screen shake: nudges the camera after it is placed. Never touches the player's real aim. */
@Mixin(Camera.class)
public abstract class CameraMixin {
	@Shadow
	protected abstract void setRotation(float yRot, float xRot);

	@Shadow
	public abstract float xRot();

	@Shadow
	public abstract float yRot();

	@Inject(method = "update", at = @At("TAIL"))
	private void jawbusslots$shake(DeltaTracker deltaTracker, CallbackInfo ci) {
		if (Cinematic.shakeActive()) {
			this.setRotation(this.yRot() + Cinematic.shakeYaw(), this.xRot() + Cinematic.shakePitch());
		}
	}
}
