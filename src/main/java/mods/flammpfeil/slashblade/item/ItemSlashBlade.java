package mods.flammpfeil.slashblade.item;

import com.google.common.collect.*;
import mods.flammpfeil.slashblade.SlashBlade;
import mods.flammpfeil.slashblade.capability.imputstate.IImputState;
import mods.flammpfeil.slashblade.capability.slashblade.ComboState;
import mods.flammpfeil.slashblade.capability.slashblade.ISlashBladeState;
import mods.flammpfeil.slashblade.entity.BladeItemEntity;
import mods.flammpfeil.slashblade.util.ImputCommand;
import mods.flammpfeil.slashblade.util.NBTHelper;
import mods.flammpfeil.slashblade.util.TimeValueHelper;
import net.minecraft.block.BlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.attributes.AttributeModifier;
import net.minecraft.entity.item.ItemEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.EquipmentSlotType;
import net.minecraft.item.*;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.item.crafting.RecipeManager;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.nbt.StringNBT;
import net.minecraft.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.*;
import net.minecraft.util.math.*;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.util.text.TranslationTextComponent;
import net.minecraft.world.World;
import net.minecraft.world.server.ServerWorld;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityInject;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.server.ServerLifecycleHooks;

import javax.annotation.Nullable;
import java.util.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public class ItemSlashBlade extends SwordItem {
    protected static final UUID ATTACK_DAMAGE_AMPLIFIER = UUID.fromString("2D988C13-595B-4E58-B254-39BB6FA077FD");
    protected static final UUID PLAYER_REACH_AMPLIFIER = UUID.fromString("2D988C13-595B-4E58-B254-39BB6FA077FD");

    @CapabilityInject(ISlashBladeState.class)
    public static Capability<ISlashBladeState> BLADESTATE = null;
    @CapabilityInject(IImputState.class)
    public static Capability<IImputState> IMPUT_STATE = null;

    public ItemSlashBlade(IItemTier tier, int attackDamageIn, float attackSpeedIn, Properties builder) {
        super(tier, attackDamageIn, attackSpeedIn, builder);
    }

    @Override
    public Multimap<String, AttributeModifier> getAttributeModifiers(EquipmentSlotType slot, ItemStack stack)
    {
        Multimap<String, AttributeModifier> result = super.getAttributeModifiers(slot,stack);

        if (slot == EquipmentSlotType.MAINHAND) {

            LazyOptional<ISlashBladeState> state = stack.getCapability(BLADESTATE);
            state.ifPresent(s -> {
                float baseAttackModifier = s.getBaseAttackModifier();
                AttributeModifier base = new AttributeModifier(ATTACK_DAMAGE_MODIFIER,
                        "Weapon modifier",
                        (double) baseAttackModifier,
                        AttributeModifier.Operation.ADDITION);
                result.remove(SharedMonsterAttributes.ATTACK_DAMAGE.getName(),base);
                result.put(SharedMonsterAttributes.ATTACK_DAMAGE.getName(),base);

                float rankAttackAmplifier = s.getAttackAmplifier();
                result.put(SharedMonsterAttributes.ATTACK_DAMAGE.getName(),
                        new AttributeModifier(ATTACK_DAMAGE_AMPLIFIER,
                                "Weapon amplifier",

                                (double) rankAttackAmplifier,
                                AttributeModifier.Operation.ADDITION));



                result.put(PlayerEntity.REACH_DISTANCE.getName(), new AttributeModifier(PLAYER_REACH_AMPLIFIER,
                        "Reach amplifer",
                        s.isBroken() ? 0 : 1.0, AttributeModifier.Operation.ADDITION));

            });
        }

        return result;
    }

    @Override
    public Rarity getRarity(ItemStack stack) {
        LazyOptional<ISlashBladeState> state = stack.getCapability(BLADESTATE);

        return state
                .filter(s -> s.getRarity() != Rarity.COMMON)
                .map(s -> s.getRarity())
                .orElseGet(() -> super.getRarity(stack));

    }


    public int getUseDuration(ItemStack stack) {
        return 72000;
    }

    public ActionResult<ItemStack> onItemRightClick(World worldIn, PlayerEntity playerIn, Hand handIn) {
        ItemStack itemstack = playerIn.getHeldItem(handIn);

        boolean result = itemstack.getCapability(BLADESTATE).map((state) -> {

            playerIn.getCapability(IMPUT_STATE).ifPresent((s)->s.getCommands().add(ImputCommand.R_CLICK));

            ComboState combo = state.progressCombo(playerIn);
            state.setLastActionTime(worldIn.getGameTime());
            combo.clickAction(playerIn);

            playerIn.getCapability(IMPUT_STATE).ifPresent((s)->s.getCommands().remove(ImputCommand.R_CLICK));

            if(combo != ComboState.NONE)
                playerIn.swingArm(handIn);

            return true;
        }).orElse(false);

        playerIn.setActiveHand(handIn);
        return new ActionResult<>(ActionResultType.SUCCESS, itemstack);
    }

    @Override
    public boolean onLeftClickEntity(ItemStack itemstack, PlayerEntity playerIn, Entity entity) {

        World worldIn = playerIn.world;

        LazyOptional<ISlashBladeState> stateHolder = itemstack.getCapability(BLADESTATE)
                .filter((state) -> !state.onClick());

        stateHolder.ifPresent((state) -> {
            playerIn.getCapability(IMPUT_STATE).ifPresent((s)->s.getCommands().add(ImputCommand.L_CLICK));

            ComboState combo = state.progressCombo(playerIn);
            state.setLastActionTime(worldIn.getGameTime());
            combo.clickAction(playerIn);

            playerIn.getCapability(IMPUT_STATE).ifPresent((s)->s.getCommands().remove(ImputCommand.L_CLICK));
        });

        return stateHolder.isPresent();
    }

    private Consumer<LivingEntity> getOnBroken(ItemStack stack){
        return (user)->{
            user.sendBreakAnimation(user.getActiveHand());

            ItemStack soul = new ItemStack(SBItems.proudsoul);

            CompoundNBT blade = stack.write(new CompoundNBT());
            soul.setTagInfo("BladeData", blade);

            stack.getCapability(BLADESTATE).ifPresent(s->{
                s.getTexture().ifPresent(r->soul.setTagInfo("Texture", StringNBT.valueOf(r.toString())));
                s.getModel().ifPresent(r->soul.setTagInfo("Model", StringNBT.valueOf(r.toString())));
            });

            ItemEntity itementity = new ItemEntity(user.world, user.getPosX(), user.getPosY() , user.getPosZ(), soul);
            BladeItemEntity e = new BladeItemEntity(SlashBlade.RegistryEvents.BladeItem, user.world);

            e.copyDataFromOld(itementity);
            e.init();
            e.addVelocity(0,0.4,0);

            e.setPickupDelay(20*2);
            e.setGlowing(true);

            e.setAir(-1);

            user.world.addEntity(e);

        };
    }

    @Override
    public boolean hitEntity(ItemStack stackF, LivingEntity target, LivingEntity attacker) {

        ItemStack stack = attacker.getHeldItemMainhand();

        stack.getCapability(BLADESTATE).ifPresent((state)->{
            state.resolvCurrentComboState(attacker).hitEffect(target);

            state.damageBlade(stack, 1, attacker, this.getOnBroken(stack));

        });

        return true;
    }
    public boolean onBlockDestroyed(ItemStack stack, World worldIn, BlockState state, BlockPos pos, LivingEntity entityLiving) {

        if (state.getBlockHardness(worldIn, pos) != 0.0F) {
            stack.getCapability(BLADESTATE).ifPresent((s)->{
                s.damageBlade(stack, 1, entityLiving, this.getOnBroken(stack));
            });
        }

        return true;
    }

    @Override
    public void onPlayerStoppedUsing(ItemStack stack, World worldIn, LivingEntity entityLiving, int timeLeft) {
        int elapsed = this.getUseDuration(stack) - timeLeft;

        if (!worldIn.isRemote) {

            stack.getCapability(BLADESTATE).ifPresent((state) -> {

                ComboState sa = state.progressCombo(entityLiving, elapsed);

                //sa.tickAction(entityLiving);
                if (sa != ComboState.NONE){
                    state.damageBlade(stack, 1, entityLiving, this.getOnBroken(stack));
                    entityLiving.swingArm(Hand.MAIN_HAND);
                }
            });
        }
    }

    @Override
    public void onUsingTick(ItemStack stack, LivingEntity player, int count) {
        stack.getCapability(BLADESTATE).ifPresent((state)->{
            state.getComboSeq().holdAction(player);

            if(!player.world.isRemote){
                int ticks = player.getItemInUseMaxCount();
                if(0 < ticks){

                    if( ticks == 20){//state.getFullChargeTicks(player)){
                        Vec3d pos = player.getEyePosition(0).add(player.getLookVec());
                        ((ServerWorld)player.world).spawnParticle(ParticleTypes.PORTAL,pos.x,pos.y,pos.z, 7, 0.7,0.7,0.7, 0.02);

                    }else{
                        ComboState old = state.getComboSeq();
                        ComboState current = state.resolvCurrentComboState(player);

                        if(current.getQuickChargeEnabled()){
                            if(old != current){
                                ticks -= TimeValueHelper.getTicksFromMSec(old.getTimeoutMS());
                            }

                            int quickChargeTicks = (int)(TimeValueHelper.getTicksFromFrames(current.getEndFrame() - current.getStartFrame()) * (1.0f / current.getSpeed()));

                            if((quickChargeTicks == ticks)){
                                Vec3d pos = player.getEyePosition(0).add(player.getLookVec());
                                ((ServerWorld)player.world).spawnParticle(ParticleTypes.ENCHANTED_HIT,pos.x,pos.y,pos.z, 7, 0.7,0.7,0.7, 0.02);
                            }
                        }
                    }

                }
            }
        });
    }

    @Override
    public void inventoryTick(ItemStack stack, World worldIn, Entity entityIn, int itemSlot, boolean isSelected) {
        super.inventoryTick(stack, worldIn, entityIn, itemSlot, isSelected);

        if(!isSelected) return;

        if(stack == null)
            return;
        if(entityIn == null)
            return;

        stack.getCapability(BLADESTATE).ifPresent((state)->{
            if(entityIn instanceof LivingEntity){
                /*
                if(0.5f > state.getDamage())
                    state.setDamage(0.99f);
                */

                state.resolvCurrentComboState((LivingEntity)entityIn).tickAction((LivingEntity)entityIn);
                state.sendChanges(entityIn);
            }
        });
    }

    @Nullable
    @Override
    public CompoundNBT getShareTag(ItemStack stack) {



        return stack.getCapability(ItemSlashBlade.BLADESTATE)
                .filter(s->s.getShareTag() != null)
                .map(s->{
                    CompoundNBT tag = s.getShareTag();
                    if(tag.getBoolean("isBroken") != s.isBroken())
                        tag.putString("isBroken",Boolean.toString(s.isBroken()));

                    stack.setTagInfo("ShareTag", tag);

                    return tag;
                })
                .orElseGet(()-> {

                    stack.getCapability(ItemSlashBlade.BLADESTATE).ifPresent(s->{
                        NBTHelper.getNBTCoupler(stack.getOrCreateTag())
                                .getChild("ShareTag").
                                put("translationKey", s.getTranslationKey()).
                                put("isBroken", Boolean.toString(s.isBroken())).
                                put("isNoScabbard", Boolean.toString(s.isNoScabbard()));
                    });

                    CompoundNBT tag = stack.write(new CompoundNBT()).copy();

                    NBTHelper.getNBTCoupler(tag)
                            .getChild("ForgeCaps")
                            .getChild("slashblade:bladestate")
                            .doRawCompound("State", ISlashBladeState::removeActiveState);

                    stack.getCapability(ItemSlashBlade.BLADESTATE).ifPresent(s->s.setShareTag(tag));

                    return tag;
                });

    }

    public static final String ICON_TAG_KEY = "SlashBladeIcon";
    public static final String CLIENT_CAPS_KEY = "AllCapsData";

    @Override
    public void readShareTag(ItemStack stack, @Nullable CompoundNBT nbt) {
        if(nbt == null)
            return;
        if(nbt.contains(ICON_TAG_KEY)) {
            stack.deserializeNBT(nbt.getCompound(ICON_TAG_KEY));
            return;
        }

        if(nbt.contains(CLIENT_CAPS_KEY,10)){
            stack.deserializeNBT(nbt.getCompound(CLIENT_CAPS_KEY));
        }else{
            stack.deserializeNBT(nbt);
        }

        DistExecutor.runWhenOn(Dist.CLIENT, ()->()->{
            CompoundNBT tag = nbt.copy();
            tag.remove(CLIENT_CAPS_KEY);
            stack.setTagInfo(CLIENT_CAPS_KEY, tag);
        });
    }

    //damage ----------------------------------------------------------
    int getHalfMaxdamage(){
        return this.getMaxDamage() / 2;
    }
    
    @Override
    public int getDamage(ItemStack stack) {
        return getHalfMaxdamage();
    }

    @Override
    public void setDamage(ItemStack stack, int damage) {
        if(damage == getHalfMaxdamage())
            return;
        
        //anti shrink damageItem
        if(damage > stack.getMaxDamage())
            stack.setCount(2);

        stack.getCapability(BLADESTATE).ifPresent((s)->{
            float amount = (damage - getHalfMaxdamage()) / (float)this.getMaxDamage();

            s.setDamage(s.getDamage() + amount);
        });
    }

    @Override
    public boolean isDamaged(ItemStack stack) {
        return stack.getCapability(BLADESTATE).map(s->0 < s.getDamage()).orElse(false);
    }

    @Override
    public <T extends LivingEntity> int damageItem(ItemStack stack, int amount, T entity, Consumer<T> onBroken) {
        return Math.min(amount, getHalfMaxdamage() / 2);
    }

    @Override
    public boolean showDurabilityBar(ItemStack stack) {
        return Minecraft.getInstance().player.getHeldItemMainhand() == stack;

        //super.showDurabilityBar(stack);
        //return false;
    }

    @Override
    public double getDurabilityForDisplay(ItemStack stack) {
        return stack.getCapability(BLADESTATE).map(s->s.getDamage()).orElse(0.0f);
        //return super.getDurabilityForDisplay(stack);
    }

    @Override
    public int getRGBDurabilityForDisplay(ItemStack stack) {
        boolean isBroken = stack.getCapability(BLADESTATE).filter(s->s.isBroken()).isPresent();

        return isBroken ? 0xFF66AE : 0x02E0EE;
    }

    @Override
    public String getTranslationKey(ItemStack stack) {
        return stack.getCapability(BLADESTATE)
                .filter((s)->!s.getTranslationKey().isEmpty())
                .map((state)->state.getTranslationKey())
                .orElseGet(()->super.getTranslationKey(stack));
    }

    @Override
    protected boolean isInGroup(ItemGroup group) {
        if(group == SlashBlade.SLASHBLADE)
            return true;
        else
            return super.isInGroup(group);
    }

    @OnlyIn(Dist.CLIENT)
    public static RecipeManager getClientRM(){
        ClientWorld cw = Minecraft.getInstance().world;
        if(cw != null)
            return cw.getRecipeManager();
        else
            return null;
    }
    public static RecipeManager getServerRM(){
        MinecraftServer sw = ServerLifecycleHooks.getCurrentServer();
        if(sw != null)
            return sw.getRecipeManager();
        else
            return null;
    }

    @Override
    public void fillItemGroup(ItemGroup group, NonNullList<ItemStack> items) {
        super.fillItemGroup(group, items);

        if(group == SlashBlade.SLASHBLADE){
            RecipeManager rm = DistExecutor.runForDist(()->ItemSlashBlade::getClientRM, ()->ItemSlashBlade::getServerRM);

            if(rm == null) return;

            Set<ResourceLocation> keys =rm.getKeys()
                    .filter((loc)->loc.getNamespace().equals(SlashBlade.modid)
                            && (!(
                                    loc.getPath().startsWith("material")
                                    || loc.getPath().startsWith("bladestand")
                                    || loc.getPath().startsWith("simple_slashblade"))))
                    .collect(Collectors.toSet());

            List<ItemStack> allItems = keys.stream()
                    .map(key->rm.getRecipe(key)
                            .map(r->{
                                ItemStack stack = ((IRecipe) r).getRecipeOutput().copy();
                                stack.readShareTag(stack.getShareTag());
                                return stack;
                            })
                            .orElseGet(()->ItemStack.EMPTY))
                    .sorted(Comparator.comparing(s->((ItemStack)s).getTranslationKey()).reversed())
                    .collect(Collectors.toList());

            items.addAll(allItems);
        }
    }

    @Override
    public boolean getIsRepairable(ItemStack toRepair, ItemStack repair) {
        /*
        Tag<Item> tags = ItemTags.getCollection().get(new ResourceLocation("slashblade","proudsouls"));

        if(tags != null){
            boolean result = Ingredient.fromTag(tags).test(repair);
        }*/

        //todo: repair custom material

        return super.getIsRepairable(toRepair, repair);
    }

    RangeMap refineColor = ImmutableRangeMap.builder()
            .put(Range.lessThan(10), TextFormatting.WHITE)
            .put(Range.closedOpen(10,50), TextFormatting.YELLOW)
            .put(Range.closedOpen(50,100), TextFormatting.GREEN)
            .put(Range.closedOpen(100,150), TextFormatting.AQUA)
            .put(Range.closedOpen(150,200), TextFormatting.BLUE)
            .put(Range.atLeast(200), TextFormatting.LIGHT_PURPLE)
            .build();


    @Override
    public void addInformation(ItemStack stack, @Nullable World worldIn, List<ITextComponent> tooltip, ITooltipFlag flagIn) {
        stack.getCapability(ItemSlashBlade.BLADESTATE).ifPresent(s->{
            if(0 < s.getKillCount())
                tooltip.add(new TranslationTextComponent("slashblade.tooltip.killcount", s.getKillCount()));

            if(0 < s.getRefine()){
                tooltip.add(new TranslationTextComponent("slashblade.tooltip.refine", s.getRefine()).applyTextStyle((TextFormatting)refineColor.get(s.getRefine())));
            }
        });

        super.addInformation(stack, worldIn, tooltip, flagIn);
    }


    /**
     * @return true = cancel : false = swing
     */
    @Override
    public boolean onEntitySwing(ItemStack stack, LivingEntity entity) {
        return !stack.getCapability(BLADESTATE).filter(s->s.getLastActionTime() == entity.world.getGameTime()).isPresent();
    }

    @Override
    public boolean hasCustomEntity(ItemStack stack) {
        return true;
    }

    @Nullable
    @Override
    public Entity createEntity(World world, Entity location, ItemStack itemstack) {
        BladeItemEntity e = new BladeItemEntity(SlashBlade.RegistryEvents.BladeItem, world);
        e.copyDataFromOld(location);
        e.init();
        return e;
    }

    @Override
    public int getEntityLifespan(ItemStack itemStack, World world) {
        return super.getEntityLifespan(itemStack, world);// Short.MAX_VALUE;
    }
}
