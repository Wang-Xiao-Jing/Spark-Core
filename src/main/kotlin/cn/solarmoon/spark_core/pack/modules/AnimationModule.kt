package cn.solarmoon.spark_core.pack.modules

import cn.solarmoon.spark_core.SparkCore
import cn.solarmoon.spark_core.animation.anim.origin.OAnimationSet
import cn.solarmoon.spark_core.animation.model.ModelIndex
import cn.solarmoon.spark_core.pack.SparkPackLoader
import cn.solarmoon.spark_core.pack.graph.SparkPackage
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.mojang.serialization.JsonOps
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.GsonHelper
import software.bernie.geckolib.GeckoLibConstants
import software.bernie.geckolib.cache.GeckoLibCache
import software.bernie.geckolib.loading.json.typeadapter.KeyFramesAdapter
import software.bernie.geckolib.loading.`object`.BakedAnimations
import software.bernie.geckolib.util.CompoundException
import java.nio.charset.StandardCharsets
import kotlin.collections.set

class AnimationModule: SparkPackModule {

    override val id: String = "animations"

    override fun onStart(isClientSide: Boolean, fromServer: Boolean) {
        if (fromServer) OAnimationSet.ORIGINS.clear()
    }

    override fun read(
        namespace: String,
        pathSegments: List<String>,
        fileName: String,
        content: ByteArray,
        pack: SparkPackage,
        isClientSide: Boolean,
        fromServer: Boolean
    ) {
        if (!fromServer) return
        if (pathSegments.size < 2) throw IllegalArgumentException("动画的文件路径必须指向一个具体的模型名称（如：minecraft/animations/entity/player/test.json 指向名为 minecraft:player 的entity类模型，test.json为该模型下的动画）")
        val json = JsonParser.parseString(String(content, StandardCharsets.UTF_8))

        // 打入GeckoLib支持
        if (isClientSide && SparkPackLoader.isGeckoLib) {
            readGeckoLibBakedAnimation(pathSegments, fileName, json, pack)
        }

        val animationSet = OAnimationSet.CODEC.decode(JsonOps.INSTANCE, json).orThrow.first
        val id = ResourceLocation.fromNamespaceAndPath(namespace, pathSegments[1])
        OAnimationSet.ORIGINS.getOrPut(ModelIndex(pathSegments[0], id)) { OAnimationSet.EMPTY }.animations.putAll(animationSet.animations)
    }

    private fun readGeckoLibBakedAnimation(
        pathSegments: List<String>,
        fileName: String,
        json: JsonElement,
        pack: SparkPackage
    ) {
        val bakedAnimationMap = GeckoLibCache.getBakedAnimations()
        if (!fileName.endsWith(".animation.json")) {
            return
        }
        val resourceLocation = location(pack, pathSegments, fileName)
        try {
            bakedAnimationMap[resourceLocation] = KeyFramesAdapter.GEO_GSON.fromJson(
                GsonHelper.getAsJsonObject(json.asJsonObject, this.id),
                BakedAnimations::class.java
            )
        } catch (ex: CompoundException) {
            ex.withMessage("$pathSegments: Error loading animation file").printStackTrace()
            BakedAnimations(Object2ObjectOpenHashMap())
        } catch (ex: Exception) {
            throw GeckoLibConstants.exception(resourceLocation, "Error loading animation file", ex)
        }
    }

    override fun onFinish(isClientSide: Boolean, fromServer: Boolean) {
        val logMsg = buildString {
            append("\n\uD83E\uDDD1\u200D\uD83E\uDDBD已为 ${OAnimationSet.ORIGINS.size} 个模型读取到动画集\uD83E\uDDD1\u200D\uD83E\uDDBD\n")
            OAnimationSet.ORIGINS.forEach { (modelId, set) ->
                append("✅$modelId: [")
                append(set.animations.keys.joinToString(", "))
                append("]\n")
            }
        }
        SparkCore.logger("动画加载器").info(logMsg)
    }

}