package dev.cloudnine.smoke.mixin;

import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Same input route used by Fabric's client game-test API, in the test mod only. */
@Mixin(MouseHandler.class)
public interface SmokeMouseInvoker {
    @Invoker("onMove") void cloudnineMove(long window, double x, double y);
    @Invoker("onButton") void cloudnineButton(long window, MouseButtonInfo info, int action);
}
