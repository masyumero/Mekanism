package mekanism.api;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.DynamicOps;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.MapDecoder.Implementation;
import com.mojang.serialization.MapLike;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import mekanism.api.annotations.NothingNullByDefault;
import mekanism.api.chemical.ChemicalStack;
import mekanism.api.chemical.ChemicalType;
import mekanism.api.chemical.ChemicalUtils;
import mekanism.api.chemical.gas.GasStack;
import mekanism.api.chemical.infuse.InfusionStack;
import mekanism.api.chemical.merged.BoxedChemicalStack;
import mekanism.api.chemical.pigment.PigmentStack;
import mekanism.api.chemical.slurry.SlurryStack;
import mekanism.api.math.FloatingLong;
import net.minecraft.Util;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.TagParser;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.util.GsonHelper;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.fluids.FluidStack;
import org.jetbrains.annotations.NotNull;

@NothingNullByDefault
public class SerializerHelper {

    private SerializerHelper() {
    }

    /**
     * Long Codec which accepts a number >= 0
     */
    public static final Codec<Long> POSITIVE_LONG_CODEC = Util.make(() -> {
        final Function<Long, DataResult<Long>> checker = Codec.checkRange(0L, Long.MAX_VALUE);
        return Codec.LONG.flatXmap(checker, checker);
    });

    /**
     * Long Codec which accepts a number > 0
     */
    public static final Codec<Long> POSITIVE_NONZERO_LONG_CODEC = Util.make(() -> {
        final Function<Long, DataResult<Long>> checker = Codec.checkRange(1L, Long.MAX_VALUE);
        return Codec.LONG.flatXmap(checker, checker);
    });

    /**
     * Codec version of the old CraftingHelper.getItemStack
     */
    public static final Codec<ItemStack> ITEMSTACK_CODEC = RecordCodecBuilder.create(instance -> instance.group(
          BuiltInRegistries.ITEM.byNameCodec().fieldOf(JsonConstants.ITEM).forGetter(ItemStack::getItem),
          ExtraCodecs.POSITIVE_INT.optionalFieldOf(JsonConstants.COUNT, 1).forGetter(ItemStack::getCount),
          TagParser.AS_CODEC.optionalFieldOf(JsonConstants.NBT).forGetter(stack -> Optional.ofNullable(stack.getTag()))
    ).apply(instance, ItemStack::new));

    /**
     * Fluid Codec which makes extra sure we don't end up with an empty/invalid fluid
     */
    private static final Codec<Fluid> NON_EMPTY_FLUID_CODEC = ExtraCodecs.validate(BuiltInRegistries.FLUID.byNameCodec(),
          fluid -> fluid == Fluids.EMPTY ? DataResult.error(() -> "Invalid fluid type") : DataResult.success(fluid));

    /**
     * Fluidstack codec to maintain compatibility with our old json
     */
    public static final Codec<FluidStack> FLUIDSTACK_CODEC = RecordCodecBuilder.create(instance -> instance.group(
          NON_EMPTY_FLUID_CODEC.fieldOf(JsonConstants.FLUID).forGetter(FluidStack::getFluid),
          ExtraCodecs.POSITIVE_INT.fieldOf(JsonConstants.AMOUNT).forGetter(FluidStack::getAmount),
          TagParser.AS_CODEC.optionalFieldOf(JsonConstants.NBT).forGetter(stack -> Optional.ofNullable(stack.getTag()))
    ).apply(instance, (fluid, amount, tag) -> {
        //Note: We don't use the constructor that accepts a tag to avoid having to copy it
        FluidStack stack = new FluidStack(fluid, amount);
        tag.ifPresent(stack::setTag);
        return stack;
    }));

    /**
     * Codec to get any kind of chemical stack, based on a "chemicalType" field.
     * See also {@link ChemicalType}
     */
    public static final Codec<ChemicalStack<?>> BOXED_CHEMICALSTACK_CODEC = ChemicalType.CODEC.dispatch(JsonConstants.CHEMICAL_TYPE, ChemicalType::getTypeFor, type -> switch (type) {
        case GAS -> ChemicalUtils.GAS_STACK_CODEC;
        case INFUSION -> ChemicalUtils.INFUSION_STACK_CODEC;
        case PIGMENT -> ChemicalUtils.PIGMENT_STACK_CODEC;
        case SLURRY -> ChemicalUtils.SLURRY_STACK_CODEC;
    });

    /**
     * Deserializes a FloatingLong that is stored in a specific key in a Json Object.
     *
     * @param json Json Object.
     * @param key  Key the FloatingLong is stored in.
     *
     * @return FloatingLong.
     * @deprecated use {@link FloatingLong#CODEC}
     */
    @Deprecated(forRemoval = true)
    public static FloatingLong getFloatingLong(@NotNull JsonObject json, @NotNull String key) {
        if (!json.has(key)) {
            throw new JsonSyntaxException("Missing '" + key + "', expected to find an object");
        }
        JsonElement jsonElement = json.get(key);
        if (!jsonElement.isJsonPrimitive()) {
            throw new JsonSyntaxException("Expected '" + key + "' to be a json primitive representing a FloatingLong");
        }
        try {
            return FloatingLong.parseFloatingLong(jsonElement.getAsNumber().toString(), true);
        } catch (NumberFormatException e) {
            throw new JsonSyntaxException("Expected '" + key + "' to be a valid FloatingLong (positive decimal number)");
        }
    }

    private static void validateKey(@NotNull JsonObject json, @NotNull String key) {
        if (!json.has(key)) {
            throw new JsonSyntaxException("Missing '" + key + "', expected to find an object");
        }
        if (!json.get(key).isJsonObject()) {
            throw new JsonSyntaxException("Expected '" + key + "' to be an object");
        }
    }

    /**
     * Gets and deserializes a Chemical Type from a given Json Object.
     *
     * @param json Json Object.
     *
     * @return Chemical Type.
     * @deprecated use {@link #BOXED_CHEMICALSTACK_CODEC} or {@link ChemicalType#CODEC}
     */
    @Deprecated(forRemoval = true)
    public static ChemicalType getChemicalType(@NotNull JsonObject json) {
        if (!json.has(JsonConstants.CHEMICAL_TYPE)) {
            throw new JsonSyntaxException("Missing '" + JsonConstants.CHEMICAL_TYPE + "', expected to find a string");
        }
        JsonElement element = json.get(JsonConstants.CHEMICAL_TYPE);
        if (!element.isJsonPrimitive()) {
            throw new JsonSyntaxException("Expected '" + JsonConstants.CHEMICAL_TYPE + "' to be a json primitive representing a string");
        }
        String name = element.getAsString();
        ChemicalType chemicalType = ChemicalType.fromString(name);
        if (chemicalType == null) {
            throw new JsonSyntaxException("Invalid chemical type '" + name + "'.");
        }
        return chemicalType;
    }

    /**
     * Helper to get and deserialize an Item Stack from a specific sub-element in a Json Object.
     *
     * @param json Parent Json Object
     * @param key  Key in the Json Object that contains an Item Stack.
     *
     * @return Item Stack.
     * @deprecated use {@link #ITEMSTACK_CODEC}
     */
    @Deprecated(forRemoval = true)
    public static ItemStack getItemStack(@NotNull JsonObject json, @NotNull String key) {
        validateKey(json, key);
        return ITEMSTACK_CODEC.parse(JsonOps.INSTANCE, GsonHelper.getAsJsonObject(json, key)).getOrThrow(false, unused->{});
    }

    /**
     * Helper to get and deserialize a Fluid Stack from a specific sub-element in a Json Object.
     *
     * @param json Parent Json Object
     * @param key  Key in the Json Object that contains a Fluid Stack.
     *
     * @return Fluid Stack.
     * @deprecated use {@link #FLUIDSTACK_CODEC}
     */
    @Deprecated(forRemoval = true)
    public static FluidStack getFluidStack(@NotNull JsonObject json, @NotNull String key) {
        validateKey(json, key);
        return deserializeFluid(GsonHelper.getAsJsonObject(json, key));
    }

    /**
     * Helper to get and deserialize a Chemical Stack from a specific sub-element in a Json Object.
     *
     * @param json Parent Json Object
     * @param key  Key in the Json Object that contains a Chemical Stack.
     *
     * @return Chemical Stack.
     * @deprecated use {@link #BOXED_CHEMICALSTACK_CODEC}
     */
    @Deprecated(forRemoval = true)
    public static ChemicalStack<?> getBoxedChemicalStack(@NotNull JsonObject json, @NotNull String key) {
        validateKey(json, key);
        JsonObject jsonObject = GsonHelper.getAsJsonObject(json, key);
        ChemicalType chemicalType = getChemicalType(jsonObject);
        return switch (chemicalType) {
            case GAS -> deserializeGas(jsonObject);
            case INFUSION -> deserializeInfuseType(jsonObject);
            case PIGMENT -> deserializePigment(jsonObject);
            case SLURRY -> deserializeSlurry(jsonObject);
        };
    }

    /**
     * Helper to get and deserialize a Gas Stack from a specific sub-element in a Json Object.
     *
     * @param json Parent Json Object
     * @param key  Key in the Json Object that contains a Gas Stack.
     *
     * @return Gas Stack.
     * @deprecated use {@link ChemicalUtils#GAS_STACK_CODEC}
     */
    @Deprecated(forRemoval = true)
    public static GasStack getGasStack(@NotNull JsonObject json, @NotNull String key) {
        validateKey(json, key);
        return deserializeGas(GsonHelper.getAsJsonObject(json, key));
    }

    /**
     * Helper to get and deserialize an Infusion Stack from a specific sub-element in a Json Object.
     *
     * @param json Parent Json Object
     * @param key  Key in the Json Object that contains an Infusion Stack.
     *
     * @return Infusion Stack.
     * @deprecated use {@link ChemicalUtils#INFUSION_STACK_CODEC}
     */
    @Deprecated(forRemoval = true)
    public static InfusionStack getInfusionStack(@NotNull JsonObject json, @NotNull String key) {
        validateKey(json, key);
        return deserializeInfuseType(GsonHelper.getAsJsonObject(json, key));
    }

    /**
     * Helper to get and deserialize a Pigment Stack from a specific sub-element in a Json Object.
     *
     * @param json Parent Json Object
     * @param key  Key in the Json Object that contains a Pigment Stack.
     *
     * @return Pigment Stack.
     * @deprecated use {@link ChemicalUtils#PIGMENT_STACK_CODEC}
     */
    @Deprecated(forRemoval = true)
    public static PigmentStack getPigmentStack(@NotNull JsonObject json, @NotNull String key) {
        validateKey(json, key);
        return deserializePigment(GsonHelper.getAsJsonObject(json, key));
    }

    /**
     * Helper to get and deserialize a Slurry Stack from a specific sub-element in a Json Object.
     *
     * @param json Parent Json Object
     * @param key  Key in the Json Object that contains a Slurry Stack.
     *
     * @return Slurry Stack.
     * @deprecated use {@link ChemicalUtils#SLURRY_STACK_CODEC}
     */
    @Deprecated(forRemoval = true)
    public static SlurryStack getSlurryStack(@NotNull JsonObject json, @NotNull String key) {
        validateKey(json, key);
        return deserializeSlurry(GsonHelper.getAsJsonObject(json, key));
    }

    /**
     * Helper to deserialize a Json Object into a Fluid Stack.
     *
     * @param json Json object to deserialize.
     *
     * @return Fluid Stack.
     * @deprecated use {@link #FLUIDSTACK_CODEC}
     */
    @Deprecated(forRemoval = true)
    public static FluidStack deserializeFluid(@NotNull JsonObject json) {
        return FLUIDSTACK_CODEC.parse(JsonOps.INSTANCE, json).getOrThrow(false, unused -> {});
    }

    /**
     * Helper to deserialize a Json Object into a Gas Stack.
     *
     * @param json Json object to deserialize.
     *
     * @return Gas Stack.
     * @deprecated use {@link ChemicalUtils#GAS_STACK_CODEC}
     */
    @Deprecated(forRemoval = true)
    public static GasStack deserializeGas(@NotNull JsonObject json) {
        return ChemicalUtils.GAS_STACK_CODEC.parse(JsonOps.INSTANCE, json).getOrThrow(false, unused -> {});
    }

    /**
     * Helper to deserialize a Json Object into an Infusion Stack.
     *
     * @param json Json object to deserialize.
     *
     * @return Infusion Stack.
     * @deprecated use {@link ChemicalUtils#INFUSION_STACK_CODEC}
     */
    @Deprecated(forRemoval = true)
    public static InfusionStack deserializeInfuseType(@NotNull JsonObject json) {
        return ChemicalUtils.INFUSION_STACK_CODEC.parse(JsonOps.INSTANCE, json).getOrThrow(false, unused -> {});
    }

    /**
     * Helper to deserialize a Json Object into a Pigment Stack.
     *
     * @param json Json object to deserialize.
     *
     * @return Pigment Stack.
     * @deprecated use {@link ChemicalUtils#PIGMENT_STACK_CODEC}
     */
    @Deprecated(forRemoval = true)
    public static PigmentStack deserializePigment(@NotNull JsonObject json) {
        return ChemicalUtils.PIGMENT_STACK_CODEC.parse(JsonOps.INSTANCE, json).getOrThrow(false, unused -> {});
    }

    /**
     * Helper to deserialize a Json Object into a Slurry Stack.
     *
     * @param json Json object to deserialize.
     *
     * @return Slurry Stack.
     * @deprecated use {@link ChemicalUtils#SLURRY_STACK_CODEC}
     */
    @Deprecated(forRemoval = true)
    public static SlurryStack deserializeSlurry(@NotNull JsonObject json) {
        return ChemicalUtils.SLURRY_STACK_CODEC.parse(JsonOps.INSTANCE, json).getOrThrow(false, unused -> {});
    }

    /**
     * Helper to serialize an Item Stack into a Json Object.
     *
     * @param stack Stack to serialize.
     *
     * @return Json representation.
     */
    public static JsonElement serializeItemStack(@NotNull ItemStack stack) {
        return ITEMSTACK_CODEC.encodeStart(JsonOps.INSTANCE, stack).getOrThrow(false, unused->{});
    }

    /**
     * Helper to serialize a Fluid Stack into a Json Object.
     *
     * @param stack Stack to serialize.
     *
     * @return Json representation.
     */
    public static JsonElement serializeFluidStack(@NotNull FluidStack stack) {
        return FLUIDSTACK_CODEC.encodeStart(JsonOps.INSTANCE, stack).getOrThrow(false, unused->{});
    }

    /**
     * Helper to serialize a Boxed Chemical Stack into a Json Object.
     *
     * @param stack Stack to serialize.
     *
     * @return Json representation.
     */
    public static JsonElement serializeBoxedChemicalStack(@NotNull BoxedChemicalStack stack) {
        return BOXED_CHEMICALSTACK_CODEC.encodeStart(JsonOps.INSTANCE, stack.getChemicalStack()).getOrThrow(false, unused->{});
    }

    /**
     * Helper to serialize a Gas Stack into a Json Object.
     *
     * @param stack Stack to serialize.
     *
     * @return Json representation.
     */
    public static JsonObject serializeGasStack(@NotNull GasStack stack) {
        return ChemicalUtils.GAS_STACK_CODEC.encodeStart(JsonOps.INSTANCE, stack).getOrThrow(false, unused->{}).getAsJsonObject();
    }

    /**
     * Helper to serialize an Infusion Stack into a Json Object.
     *
     * @param stack Stack to serialize.
     *
     * @return Json representation.
     */
    public static JsonObject serializeInfusionStack(@NotNull InfusionStack stack) {
        return ChemicalUtils.INFUSION_STACK_CODEC.encodeStart(JsonOps.INSTANCE, stack).getOrThrow(false, unused->{}).getAsJsonObject();
    }

    /**
     * Helper to serialize a Pigment Stack into a Json Object.
     *
     * @param stack Stack to serialize.
     *
     * @return Json representation.
     */
    public static JsonObject serializePigmentStack(@NotNull PigmentStack stack) {
        return ChemicalUtils.PIGMENT_STACK_CODEC.encodeStart(JsonOps.INSTANCE, stack).getOrThrow(false, unused->{}).getAsJsonObject();
    }

    /**
     * Helper to serialize a Slurry Stack into a Json Object.
     *
     * @param stack Stack to serialize.
     *
     * @return Json representation.
     */
    public static JsonObject serializeSlurryStack(@NotNull SlurryStack stack) {
        return ChemicalUtils.SLURRY_STACK_CODEC.encodeStart(JsonOps.INSTANCE, stack).getOrThrow(false, unused->{}).getAsJsonObject();
    }

    /**
     * Generate a RecordCodecBuilder which is required only if the 'primary' is present. If this field is present, it will be returned regardless. Does not eat errors
     *
     * @param primaryField    the field which determines the required-ness. MUST be an Optional
     * @param dependentCodec  the codec for <strong>this</strong> field
     * @param dependentGetter the getter for this field (what you'd use on {@link MapCodec#forGetter(Function)})
     * @param <SOURCE>        the resulting type that both fields exist on
     * @param <THIS_TYPE>     the value type of this dependent field
     *
     * @return a RecordCodecBuilder which contains the resulting logic - use in side a `group()`
     */
    @NotNull
    public static <SOURCE, THIS_TYPE> RecordCodecBuilder<SOURCE, Optional<THIS_TYPE>> dependentOptionality(RecordCodecBuilder<SOURCE, ? extends Optional<?>> primaryField, MapCodec<Optional<THIS_TYPE>> dependentCodec, Function<SOURCE, Optional<THIS_TYPE>> dependentGetter) {
        Implementation<Optional<THIS_TYPE>> dependentRequired = new Implementation<>() {
            @Override
            public <T> DataResult<Optional<THIS_TYPE>> decode(DynamicOps<T> ops, MapLike<T> input) {
                DataResult<Optional<THIS_TYPE>> thisField = dependentCodec.decode(ops, input);

                //if the unboxed optional has a value, return this field's value.
                //if it had an error, return that
                if (thisField.error().isPresent() || thisField.result().orElse(Optional.empty()).isPresent()) {
                    return thisField;
                }

                //thisField must not be empty
                return DataResult.error(() -> "Missing value");
            }

            @Override
            public <T> Stream<T> keys(DynamicOps<T> ops) {
                return dependentCodec.keys(ops);
            }
        };
        return primaryField.dependent(dependentGetter, dependentCodec, primaryValue -> primaryValue.isEmpty() ? dependentCodec : dependentRequired);
    }

    /**
     * Generate a RecordCodecBuilder which is REQUIRED only if the 'other' is NOT present. When the other field is present, this one is OPTIONAL. Does not eat errors.
     *
     * @param otherField      the field which determines the required-ness. MUST be an Optional
     * @param dependentCodec  the codec for <strong>this</strong> field
     * @param dependentGetter the getter for this field (what you'd use on {@link MapCodec#forGetter(Function)})
     * @param <SOURCE>        the resulting type that both fields exist on
     * @param <THIS_TYPE>     the value type of this dependent field
     *
     * @return a RecordCodecBuilder which contains the resulting logic - use in side a `group()`
     */
    @NotNull
    public static <SOURCE, THIS_TYPE> RecordCodecBuilder<SOURCE, Optional<THIS_TYPE>> oneRequired(RecordCodecBuilder<SOURCE, ? extends Optional<?>> otherField, MapCodec<Optional<THIS_TYPE>> dependentCodec, Function<SOURCE, Optional<THIS_TYPE>> dependentGetter) {
        Implementation<Optional<THIS_TYPE>> dependentRequired = new Implementation<>() {
            @Override
            public <T> DataResult<Optional<THIS_TYPE>> decode(DynamicOps<T> ops, MapLike<T> input) {
                DataResult<Optional<THIS_TYPE>> thisField = dependentCodec.decode(ops, input);

                //if the unboxed optional has a value, return this field's value.
                //if it had an error, return that
                if (thisField.error().isPresent() || thisField.result().orElse(Optional.empty()).isPresent()) {
                    return thisField;
                }

                //the primary is empty, and this is also empty
                return DataResult.error(() -> getFieldNames(dependentCodec) + " is required");
            }

            private static <THIS_TYPE> String getFieldNames(MapCodec<Optional<THIS_TYPE>> codec) {
                return codec.keys(JsonOps.INSTANCE).map(JsonElement::getAsString).collect(Collectors.joining());
            }

            @Override
            public <T> Stream<T> keys(DynamicOps<T> ops) {
                return dependentCodec.keys(ops);
            }
        };
        return otherField.dependent(dependentGetter, dependentCodec, primaryValue -> primaryValue.isPresent() ? dependentCodec : dependentRequired);
    }
}