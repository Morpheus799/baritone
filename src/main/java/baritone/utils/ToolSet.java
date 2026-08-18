/*
 * This file is part of Baritone.
 *
 * Baritone is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Baritone is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with Baritone.  If not, see <https://www.gnu.org/licenses/>.
 */

package baritone.utils;

import baritone.Baritone;
import baritone.api.utils.Helper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.minecraft.world.item.enchantment.effects.EnchantmentAttributeEffect;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * A cached list of the best tools on the hotbar for any block
 *
 * @author Avery, Brady, leijurv
 */
public class ToolSet {

    /**
     * A cache mapping a {@link Block} to how long it will take to break
     * with this toolset, given the optimum tool is used.
     */
    private final Map<Block, Double> breakStrengthCache;

    /**
     * My buddy leijurv owned me so we have this to not create a new lambda instance.
     */
    private final Function<Block, Double> backendCalculation;

    private final LocalPlayer player;

    /**
     * The maximum tool material tier to consider when selecting a tool.
     * Matches the indices of {@link #materialTagsPriorityList}. -1 means no limit.
     */
    private final int maxTier;

    /**
     * Used for evaluating the material cost of a tool.
     * see {@link #getMaterialCost(ItemStack)}
     * Prefer tools with lower material cost (lower index in this list).
     */
    private static final List<TagKey<Item>> materialTagsPriorityList = List.of(
        ItemTags.WOODEN_TOOL_MATERIALS,
        ItemTags.STONE_TOOL_MATERIALS,
        ItemTags.IRON_TOOL_MATERIALS,
        ItemTags.GOLD_TOOL_MATERIALS,
        ItemTags.DIAMOND_TOOL_MATERIALS,
        ItemTags.NETHERITE_TOOL_MATERIALS
    );

    /**
     * In 1.21.2+ the tool material tags (e.g. {@link ItemTags#DIAMOND_TOOL_MATERIALS}) are no longer
     * applied to tool items, so tier detection falls back to matching vanilla item ids. Unknown
     * (e.g. modded) items are not in this map and fall through to the tag check.
     */
    private static final Map<String, Integer> VANILLA_TOOL_TIERS = Map.ofEntries(
            Map.entry("minecraft:wooden_sword", 0), Map.entry("minecraft:wooden_shovel", 0), Map.entry("minecraft:wooden_pickaxe", 0), Map.entry("minecraft:wooden_axe", 0), Map.entry("minecraft:wooden_hoe", 0),
            Map.entry("minecraft:stone_sword", 1), Map.entry("minecraft:stone_shovel", 1), Map.entry("minecraft:stone_pickaxe", 1), Map.entry("minecraft:stone_axe", 1), Map.entry("minecraft:stone_hoe", 1),
            Map.entry("minecraft:iron_sword", 2), Map.entry("minecraft:iron_shovel", 2), Map.entry("minecraft:iron_pickaxe", 2), Map.entry("minecraft:iron_axe", 2), Map.entry("minecraft:iron_hoe", 2),
            Map.entry("minecraft:golden_sword", 3), Map.entry("minecraft:golden_shovel", 3), Map.entry("minecraft:golden_pickaxe", 3), Map.entry("minecraft:golden_axe", 3), Map.entry("minecraft:golden_hoe", 3),
            Map.entry("minecraft:diamond_sword", 4), Map.entry("minecraft:diamond_shovel", 4), Map.entry("minecraft:diamond_pickaxe", 4), Map.entry("minecraft:diamond_axe", 4), Map.entry("minecraft:diamond_hoe", 4),
            Map.entry("minecraft:netherite_sword", 5), Map.entry("minecraft:netherite_shovel", 5), Map.entry("minecraft:netherite_pickaxe", 5), Map.entry("minecraft:netherite_axe", 5), Map.entry("minecraft:netherite_hoe", 5)
    );

    /**
     * The minimum tool tier required to break a block without losing its drops, hardcoded because
     * modern versions don't expose this reliably. Only blocks requiring above wooden (0) need an
     * entry. Used to prevent path clearing from wasting precious blocks with tools that are too weak.
     */
    private static final Map<String, Integer> MIN_TIER_BY_BLOCK = Map.ofEntries(
            Map.entry("minecraft:iron_ore", 1), Map.entry("minecraft:deepslate_iron_ore", 1),
            Map.entry("minecraft:copper_ore", 1), Map.entry("minecraft:deepslate_copper_ore", 1),
            Map.entry("minecraft:lapis_ore", 1), Map.entry("minecraft:deepslate_lapis_ore", 1),
            Map.entry("minecraft:diamond_ore", 2), Map.entry("minecraft:deepslate_diamond_ore", 2),
            Map.entry("minecraft:gold_ore", 2), Map.entry("minecraft:deepslate_gold_ore", 2),
            Map.entry("minecraft:emerald_ore", 2), Map.entry("minecraft:deepslate_emerald_ore", 2),
            Map.entry("minecraft:redstone_ore", 2), Map.entry("minecraft:deepslate_redstone_ore", 2),
            Map.entry("minecraft:ancient_debris", 4),
            Map.entry("minecraft:obsidian", 4), Map.entry("minecraft:crying_obsidian", 4)
    );

    /**
     * The minimum tool tier that should be used to break the given block without losing its drops.
     * 0 means no special requirement (the {@code pathClearMaxToolTier} limit applies normally).
     */
    public static int getMinTierForBlock(Block b) {
        Identifier key = BuiltInRegistries.BLOCK.getKey(b);
        return key == null ? 0 : MIN_TIER_BY_BLOCK.getOrDefault(key.toString(), 0);
    }

    private static final ItemStack PICKAXE_REFERENCE = new ItemStack(Items.DIAMOND_PICKAXE);

    /**
     * Whether breaking the given block requires a pickaxe to be effective. When the tier cap is
     * set and no in-cap pickaxe remains, path clearing stops breaking these blocks instead of
     * falling back to better tools. Blocks that don't require a pickaxe (e.g. wood or gravel)
     * never trigger the stop.
     */
    public static boolean requiresPickaxe(Block b) {
        return PICKAXE_REFERENCE.isCorrectToolForDrops(b.defaultBlockState());
    }

    /**
     * The effective tier bounds used for path clearing a block under the tier cap. Computed in one
     * place so that the cost calculation and the execution side always agree.
     *
     * @param minTier  The block's hardcoded minimum tier to drop anything, 0 if it has none
     * @param lo       The lowest allowed tier
     * @param hi       The highest allowed tier, -1 for no upper bound
     * @param backdoor Whether the block's minimum requirement exceeds the cap, which lifts the
     *                 upper bound so that the block is never broken with a tool too weak to drop it
     */
    public record SelectionBounds(int minTier, int lo, int hi, boolean backdoor) {}

    public static SelectionBounds pathClearBounds(Block b, int maxTier) {
        int minTier = maxTier >= 0 ? getMinTierForBlock(b) : 0;
        boolean backdoor = maxTier >= 0 && minTier > maxTier;
        return new SelectionBounds(minTier, backdoor ? minTier : 0, backdoor ? -1 : maxTier, backdoor);
    }

    private static final Map<String, String> lastDebugLogByContext = new HashMap<>();

    /**
     * Debug logging that only fires when the line changed for its context (everything before the
     * arrow), so that per-tick callers don't spam. The line is always appended to the
     * logs/baritone.log file, and is additionally printed to chat when chatDebug is enabled.
     */
    public static void logDebugDeduped(String line) {
        if (Baritone.settings().pathClearMaxToolTier.value < 0 && !Baritone.settings().chatDebug.value) {
            return; // keep the default setup completely quiet: no log file and no chat output
        }
        int arrow = line.indexOf(" -> ");
        String context = arrow == -1 ? line : line.substring(0, arrow);
        synchronized (lastDebugLogByContext) {
            if (line.equals(lastDebugLogByContext.get(context))) {
                return;
            }
            if (lastDebugLogByContext.size() > 100) {
                lastDebugLogByContext.clear();
            }
            lastDebugLogByContext.put(context, line);
        }
        appendToLogFile(line);
        if (Baritone.settings().chatDebug.value) {
            Helper.HELPER.logDebug(line);
        }
    }

    private static synchronized void appendToLogFile(String line) {
        try {
            Path logFile = Minecraft.getInstance().gameDirectory.toPath().resolve("logs").resolve("baritone.log");
            Files.createDirectories(logFile.getParent());
            String entry = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss")) + " " + line + System.lineSeparator();
            Files.writeString(logFile, entry, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Throwable ignored) {
            // logging must never break the game
        }
    }

    public ToolSet(LocalPlayer player) {
        this(player, -1);
    }

    public ToolSet(LocalPlayer player, int maxTier) {
        breakStrengthCache = new HashMap<>();
        this.player = player;
        this.maxTier = maxTier;

        if (Baritone.settings().considerPotionEffects.value) {
            double amplifier = potionAmplifier();
            Function<Double, Double> amplify = x -> amplifier * x;
            backendCalculation = amplify.compose(this::getBestDestructionTime);
        } else {
            backendCalculation = this::getBestDestructionTime;
        }
    }

    /**
     * Using the best tool on the hotbar, how fast we can mine this block
     *
     * @param state the blockstate to be mined
     * @return the speed of how fast we'll mine it. 1/(time in ticks)
     */
    public double getStrVsBlock(BlockState state) {
        return breakStrengthCache.computeIfAbsent(state.getBlock(), backendCalculation);
    }

    /**
     * Evaluate the material cost of a possible tool.
     * If all else is equal, we want to prefer the tool with the lowest material cost.
     * i.e. we want to prefer a wooden pickaxe over a stone pickaxe, if all else is equal.
     * @param itemStack a possibly empty ItemStack
     * @return values from 0 up
     */
    public static int getMaterialCost(ItemStack itemStack) {
        if (itemStack.isEmpty()) {
            return -1;
        }
        // the tool material tags are not applied to tool items on modern versions, so detect
        // vanilla tool tiers by item id first; unknown (e.g. modded) items fall through to the tags
        Identifier key = BuiltInRegistries.ITEM.getKey(itemStack.getItem());
        Integer tier = key == null ? null : VANILLA_TOOL_TIERS.get(key.toString());
        if (tier != null) {
            return tier;
        }
        for (int i = 0; i < materialTagsPriorityList.size(); i++) {
            final TagKey<Item> tag = materialTagsPriorityList.get(i);
            if (itemStack.is(tag)) return i;
        }
        return -1;
    }

    public boolean hasSilkTouch(ItemStack stack) {
        ItemEnchantments enchantments = stack.getEnchantments();
        for (Holder<Enchantment> enchant : enchantments.keySet()) {
            // silk touch enchantment is still special cased as affecting block drops
            // not possible to add custom attribute via datapack
            if (enchant.is(Enchantments.SILK_TOUCH) && enchantments.getLevel(enchant) > 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Calculate which tool on the hotbar is best for mining, depending on an override setting,
     * related to auto tool movement cost, it will either return current selected slot, or the best slot.
     *
     * @param b the blockstate to be mined
     * @return An int containing the index in the tools array that worked best
     */

    public int getBestSlot(Block b, boolean preferSilkTouch) {
        return getBestSlot(b, preferSilkTouch, false);
    }

    public int getBestSlot(Block b, boolean preferSilkTouch, boolean pathingCalculation) {

        /*
        If we actually want know what efficiency our held item has instead of the best one
        possible, this lets us make pathing depend on the actual tool to be used (if auto tool is disabled)
        */
        if (!Baritone.settings().autoTool.value && pathingCalculation) {
            return player.getInventory().getSelectedSlot();
        }

        // When the tier cap is set, path clearing only uses tools within the cap (for blocks that
        // need a pickaxe, only pickaxes within the cap, or above the block's minimum tier as a
        // backdoor). If no eligible tool can be found at all, selection returns -1 and path
        // clearing stops instead of falling back to better tools. With the cap disabled (-1) the
        // original behavior is kept exactly.
        SelectionBounds bounds = pathClearBounds(b, this.maxTier);
        int best;
        if (this.maxTier >= 0) {
            best = getBestSlotWithinTier(b, preferSilkTouch, bounds.lo(), bounds.hi(), requiresPickaxe(b));
            if (best == -1 && pathingCalculation && Baritone.settings().allowInventory.value) {
                // for cost estimation we can assume that the best in-tier tool in the main inventory
                // will be moved to the hotbar, since execution will fetch it
                best = getBestSlotWithinTier(b, preferSilkTouch, bounds.lo(), bounds.hi(), requiresPickaxe(b), 9, 36);
            }
        } else {
            best = getBestSlotWithinTier(b, preferSilkTouch, 0, -1);
            if (best == -1) {
                best = 0; // original behavior: default to slot 0 if nothing at all can be used
            }
        }
        if (best == -1) {
            logDebugDeduped("[ToolSet] " + b + " maxTier=" + this.maxTier
                    + (bounds.minTier() > 0 ? " minTier=" + bounds.minTier() : "")
                    + " -> stopped (no eligible tool available)");
            return best;
        }
        String extra = bounds.backdoor() ? " (backdoor, block needs tier " + bounds.minTier() + ")" : "";
        ItemStack stack = player.getInventory().getItem(best);
        logDebugDeduped("[ToolSet] " + b + " maxTier=" + this.maxTier
                + (minTier > 0 ? " minTier=" + minTier : "")
                + extra
                + " -> slot " + best + " (" + (stack.isEmpty() ? "hand" : stack.getItem()) + ", tier " + getMaterialCost(stack) + ")");
        return best;
    }

    public int getBestSlotWithinTier(Block b, boolean preferSilkTouch, int maxTier) {
        return getBestSlotWithinTier(b, preferSilkTouch, 0, maxTier, false);
    }

    public int getBestSlotWithinTier(Block b, boolean preferSilkTouch, int minTier, int maxTier) {
        return getBestSlotWithinTier(b, preferSilkTouch, minTier, maxTier, false);
    }

    public int getBestSlotWithinTier(Block b, boolean preferSilkTouch, int minTier, int maxTier, boolean pickaxeOnly) {
        return getBestSlotWithinTier(b, preferSilkTouch, minTier, maxTier, pickaxeOnly, 0, 9);
    }

    /**
     * The best tool within the tier limit in the main inventory (excluding the hotbar), or -1 if there is none
     */
    public int getBestBackpackSlotWithinTier(Block b, boolean preferSilkTouch, int maxTier) {
        return getBestBackpackSlotWithinTier(b, preferSilkTouch, 0, maxTier, false);
    }

    public int getBestBackpackSlotWithinTier(Block b, boolean preferSilkTouch, int minTier, int maxTier) {
        return getBestBackpackSlotWithinTier(b, preferSilkTouch, minTier, maxTier, false);
    }

    public int getBestBackpackSlotWithinTier(Block b, boolean preferSilkTouch, int minTier, int maxTier, boolean pickaxeOnly) {
        return getBestSlotWithinTier(b, preferSilkTouch, minTier, maxTier, pickaxeOnly, 9, 36);
    }

    private int getBestSlotWithinTier(Block b, boolean preferSilkTouch, int minTier, int maxTier, boolean pickaxeOnly, int startIncl, int endExcl) {
        int best = -1;
        double highestSpeed = Double.NEGATIVE_INFINITY;
        int lowestCost = Integer.MIN_VALUE;
        boolean bestSilkTouch = false;
        BlockState blockState = b.defaultBlockState();
        for (int i = startIncl; i < endExcl; i++) {
            ItemStack itemStack = player.getInventory().getItem(i);
            if (!Baritone.settings().useSwordToMine.value && itemStack.is(ItemTags.SWORDS)) {
                continue;
            }

            if (Baritone.settings().itemSaver.value && (itemStack.getDamageValue() + Baritone.settings().itemSaverThreshold.value) >= itemStack.getMaxDamage() && itemStack.getMaxDamage() > 1) {
                continue;
            }
            int tier = getMaterialCost(itemStack);
            if (tier < minTier || (maxTier >= 0 && tier > maxTier)) {
                continue;
            }
            if (pickaxeOnly && !itemStack.isCorrectToolForDrops(blockState)) {
                continue;
            }
            double speed = calculateSpeedVsBlock(itemStack, blockState);
            boolean silkTouch = hasSilkTouch(itemStack);
            if (speed > highestSpeed) {
                highestSpeed = speed;
                best = i;
                lowestCost = getMaterialCost(itemStack);
                bestSilkTouch = silkTouch;
            } else if (speed == highestSpeed) {
                int cost = getMaterialCost(itemStack);
                if ((cost < lowestCost && (silkTouch || !bestSilkTouch)) ||
                        (preferSilkTouch && !bestSilkTouch && silkTouch)) {
                    highestSpeed = speed;
                    best = i;
                    lowestCost = cost;
                    bestSilkTouch = silkTouch;
                }
            }
        }
        return best;
    }

    /**
     * Calculate how effectively a block can be destroyed
     *
     * @param b the blockstate to be mined
     * @return A double containing the destruction ticks with the best tool
     */
    private double getBestDestructionTime(Block b) {
        int slot = getBestSlot(b, false, true);
        if (slot == -1) {
            // no eligible tool remains (e.g. the in-cap pickaxe is exhausted), treat the block as
            // unbreakable so that pathing stops mining it
            return -1;
        }
        ItemStack stack = player.getInventory().getItem(slot);
        return calculateSpeedVsBlock(stack, b.defaultBlockState()) * avoidanceMultiplier(b);
    }

    private double avoidanceMultiplier(Block b) {
        return Baritone.settings().blocksToAvoidBreaking.value.contains(b) ? Baritone.settings().avoidBreakingMultiplier.value : 1;
    }

    /**
     * Calculates how long would it take to mine the specified block given the best tool
     * in this toolset is used. A negative value is returned if the specified block is unbreakable.
     *
     * @param item  the item to mine it with
     * @param state the blockstate to be mined
     * @return how long it would take in ticks
     */
    public static double calculateSpeedVsBlock(ItemStack item, BlockState state) {
        float hardness;
        try {
            hardness = state.getDestroySpeed(null, null);
        } catch (NullPointerException npe) {
            // can't easily determine the hardness so treat it as unbreakable
            return -1;
        }
        if (hardness < 0) {
            return -1;
        }

        float speed = item.getDestroySpeed(state);
        if (speed > 1) {
            final ItemEnchantments itemEnchantments = item.getEnchantments();
            OUTER: for (Holder<Enchantment> enchant : itemEnchantments.keySet()) {
                List<EnchantmentAttributeEffect> effects = enchant.value().getEffects(EnchantmentEffectComponents.ATTRIBUTES);
                for (EnchantmentAttributeEffect e : effects) {
                    if (e.attribute().is(Attributes.MINING_EFFICIENCY.unwrapKey().get())) {
                        speed += e.amount().calculate(itemEnchantments.getLevel(enchant));
                        break OUTER;
                    }
                }
            }
        }

        speed /= hardness;
        if (!state.requiresCorrectToolForDrops() || (!item.isEmpty() && item.isCorrectToolForDrops(state))) {
            return speed / 30;
        } else {
            return speed / 100;
        }
    }

    /**
     * Calculates any modifier to breaking time based on status effects.
     *
     * @return a double to scale block breaking speed.
     */
    private double potionAmplifier() {
        double speed = 1;
        if (player.hasEffect(MobEffects.HASTE)) {
            speed *= 1 + (player.getEffect(MobEffects.HASTE).getAmplifier() + 1) * 0.2;
        }
        if (player.hasEffect(MobEffects.MINING_FATIGUE)) {
            switch (player.getEffect(MobEffects.MINING_FATIGUE).getAmplifier()) {
                case 0:
                    speed *= 0.3;
                    break;
                case 1:
                    speed *= 0.09;
                    break;
                case 2:
                    speed *= 0.0027; // you might think that 0.09*0.3 = 0.027 so that should be next, that would make too much sense. it's 0.0027.
                    break;
                default:
                    speed *= 0.00081;
                    break;
            }
        }
        return speed;
    }
}
