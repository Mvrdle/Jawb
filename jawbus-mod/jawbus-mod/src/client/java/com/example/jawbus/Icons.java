package com.example.jawbus;

import com.google.common.collect.ImmutableMultimap;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ResolvableProfile;

import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

/**
 * Item icons for the rewards. Items that are paper in SkyBlock use the SkyBlock pack's own item model when
 * the loaded resource packs contain it; the three head items use their skin textures (no network involved).
 */
final class Icons {
    private Icons() {
    }

    private static final String TEX_CARMINE =
            "ewogICJ0aW1lc3RhbXAiIDogMTc0MTE2MDk2ODI0MiwKICAicHJvZmlsZUlkIiA6ICJiMzMwZWE4OWE3MTY0OWZjYTMwZmI5ZjU5MTQ5ZjNjMCIsCiAgInByb2ZpbGVOYW1lIiA6ICJUQUxLX0FMT1RfIiwKICAic2lnbmF0dXJlUmVxdWlyZWQiIDogdHJ1ZSwKICAidGV4dHVyZXMiIDogewogICAgIlNLSU4iIDogewogICAgICAidXJsIiA6ICJodHRwOi8vdGV4dHVyZXMubWluZWNyYWZ0Lm5ldC90ZXh0dXJlLzgxNTBiNDJlNjQ5NzEzNzIxN2Q1ZDhmNTY1MDNmOThkOGY1NDljYjAzNGZiZWFlNmZkZDU0MzE1NzA1MThlMzgiLAogICAgICAibWV0YWRhdGEiIDogewogICAgICAgICJtb2RlbCIgOiAic2xpbSIKICAgICAgfQogICAgfQogIH0KfQ==";
    private static final String TEX_VIAL =
            "ewogICJ0aW1lc3RhbXAiIDogMTYxMDA0OTczMzc3OSwKICAicHJvZmlsZUlkIiA6ICIxOTI1MjFiNGVmZGI0MjVjODkzMWYwMmE4NDk2ZTExYiIsCiAgInByb2ZpbGVOYW1lIiA6ICJTZXJpYWxpemFibGUiLAogICJzaWduYXR1cmVSZXF1aXJlZCIgOiB0cnVlLAogICJ0ZXh0dXJlcyIgOiB7CiAgICAiU0tJTiIgOiB7CiAgICAgICJ1cmwiIDogImh0dHA6Ly90ZXh0dXJlcy5taW5lY3JhZnQubmV0L3RleHR1cmUvOWI1NzRjMDNiMWM4ZjUyNTM1Zjk2YTIwYTQxNzYwMjBiYjI4MGUwYWY1MmZjMTI4NWQzZWNiZDM1MTcyODBjNCIKICAgIH0KICB9Cn0=";

    private static final Map<Reward, ItemStack> CACHE = new EnumMap<>(Reward.class);

    /** Forget cached icons (called per animation so newly loaded resource packs are picked up). */
    static void refresh() {
        CACHE.clear();
    }

    static ItemStack get(Reward r) {
        return CACHE.computeIfAbsent(r, Icons::build);
    }

    /** One line per reward for /jawbusRoll textures. */
    static String status(Reward r) {
        String model = model(r);
        if (model != null) return modelAvailable(id(model)) ? "SkyBlock pack texture found" : "pack texture NOT found, using vanilla icon";
        return "player head skin";
    }

    private static String model(Reward r) {
        return switch (r) {
            case SILVER_MAGMAFISH -> "hypixel_skyblock:item/fishing/trophy/lava/magmafish_silver";
            case MAGMA_LORD_FRAGMENT -> "hypixel_skyblock:item/uncategorized/magma_lord_fragment";
            case BOBBIN_SCRIPTURES -> "hypixel_skyblock:item/island_relevant/crimson_isle/bobbin_time/bobbin_scriptures";
            default -> null;
        };
    }

    private static Identifier id(String s) {
        int i = s.indexOf(':');
        return Identifier.fromNamespaceAndPath(s.substring(0, i), s.substring(i + 1));
    }

    private static boolean modelAvailable(Identifier model) {
        Identifier def = Identifier.fromNamespaceAndPath(model.getNamespace(), "items/" + model.getPath() + ".json");
        return Minecraft.getInstance().getResourceManager().getResource(def).isPresent();
    }

    private static Item fallback(Reward r) {
        return switch (r) {
            case SILVER_MAGMAFISH -> Items.COD;
            case MAGMA_LORD_FRAGMENT -> Items.MAGMA_CREAM;
            case BOBBIN_SCRIPTURES -> Items.ENCHANTED_BOOK;
            case CARMINE_DYE -> Items.REDSTONE;
            case RADIOACTIVE_VIAL -> Items.EXPERIENCE_BOTTLE;
        };
    }

    private static ItemStack build(Reward r) {
        switch (r) {
            case CARMINE_DYE:
                return head(TEX_CARMINE);
            case RADIOACTIVE_VIAL:
                return head(TEX_VIAL);
            default:
                break;
        }
        String model = model(r);
        if (model != null && modelAvailable(id(model))) {
            ItemStack stack = new ItemStack(Items.PAPER);
            stack.set(DataComponents.ITEM_MODEL, id(model));
            return stack;
        }
        return new ItemStack(fallback(r));
    }

    private static ItemStack head(String textureValue) {
        ItemStack stack = new ItemStack(Items.PLAYER_HEAD);
        PropertyMap props = new PropertyMap(ImmutableMultimap.of("textures", new Property("textures", textureValue)));
        UUID uuid = UUID.nameUUIDFromBytes(textureValue.getBytes(StandardCharsets.UTF_8));
        stack.set(DataComponents.PROFILE, ResolvableProfile.createResolved(new GameProfile(uuid, "jawbus", props)));
        return stack;
    }
}
