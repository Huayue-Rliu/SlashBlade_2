package mods.flammpfeil.slashblade.ability;

import mods.flammpfeil.slashblade.capability.slashblade.ComboState;
import mods.flammpfeil.slashblade.entity.IShootable;
import mods.flammpfeil.slashblade.item.ItemSlashBlade;
import mods.flammpfeil.slashblade.util.TargetSelector;
import mods.flammpfeil.slashblade.util.TimeValueHelper;
import net.minecraft.entity.Entity;
import net.minecraft.entity.IProjectile;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.projectile.AbstractArrowEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.Vec3d;

import java.util.List;

public class ArrowReflector {

    static public boolean isMatch(Entity arrow, Entity attacker){
        if(arrow == null) return false;
        if(!(arrow instanceof IProjectile)) return false;

        return true;
    }

    static public void doReflect(Entity arrow, Entity attacker){
        if(!isMatch(arrow, attacker)) return;

        arrow.velocityChanged = true;
        if (attacker != null) {
            Vec3d dir = attacker.getLookVec();

            do{
                if(attacker instanceof LivingEntity) break;

                ItemStack stack = ((LivingEntity) attacker).getHeldItemMainhand();

                if(stack.isEmpty()) break;
                if(!(stack.getItem() instanceof ItemSlashBlade)) break;

                Entity target = stack.getCapability(ItemSlashBlade.BLADESTATE).map(s->s.getTargetEntity(attacker.world)).orElse(null);
                if(target != null){
                    dir = arrow.getPositionVec().subtract(target.getEyePosition(0)).normalize();
                }else{
                    dir = arrow.getPositionVec().subtract(attacker.getLookVec().scale(10).add(attacker.getEyePosition(0))).normalize();
                }

            }while(false);


            arrow.setMotion(dir);

            ((IProjectile) arrow).shoot(dir.x, dir.y, dir.z, 1.1f, 0.5f);

            arrow.setNoGravity(true);

            if(arrow instanceof AbstractArrowEntity)
                ((AbstractArrowEntity) arrow).setIsCritical(true);

        }
    }

    static public void doTicks(LivingEntity attacker){

        ItemStack stack = attacker.getHeldItemMainhand();

        if(stack.isEmpty()) return;
        if(!(stack.getItem() instanceof ItemSlashBlade)) return;

        stack.getCapability(ItemSlashBlade.BLADESTATE).ifPresent(s->{
            int ticks = attacker.getItemInUseMaxCount();

            if(ticks == 0) return;

            ComboState old = s.getComboSeq();
            ComboState current = s.resolvCurrentComboState(attacker);
            if(old != current){
                ticks -= TimeValueHelper.getTicksFromMSec(old.getTimeoutMS());
            }

            float period = TimeValueHelper.getTicksFromFrames(current.getEndFrame() - current.getStartFrame()) * (1.0f / current.getSpeed());

            if(ticks < period){
                List<Entity> founds = TargetSelector.getReflectableEntitiesWithinAABB(attacker.world, 4.0f, attacker);

                founds.forEach(e->doReflect(e, attacker));
            }
        });

    }
}
