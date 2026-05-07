package cn.solarmoon.spark_core.pack.modules

import cn.solarmoon.spark_core.SparkCore
import cn.solarmoon.spark_core.animation.model.ModelIndex
import cn.solarmoon.spark_core.animation.model.origin.*
import cn.solarmoon.spark_core.pack.SparkPackLoader
import cn.solarmoon.spark_core.pack.graph.SparkPackage
import cn.solarmoon.spark_core.util.div
import cn.solarmoon.spark_core.util.toRadians
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.mojang.serialization.JsonOps
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap
import net.minecraft.resources.ResourceLocation
import net.minecraft.util.GsonHelper
import net.minecraft.world.phys.Vec3
import org.joml.Vector2i
import software.bernie.geckolib.GeckoLibConstants
import software.bernie.geckolib.cache.GeckoLibCache
import software.bernie.geckolib.loading.json.FormatVersion
import software.bernie.geckolib.loading.json.raw.Model
import software.bernie.geckolib.loading.json.typeadapter.KeyFramesAdapter
import software.bernie.geckolib.loading.`object`.BakedAnimations
import software.bernie.geckolib.loading.`object`.BakedModelFactory
import software.bernie.geckolib.loading.`object`.GeometryTree
import software.bernie.geckolib.util.CompoundException
import java.nio.charset.StandardCharsets
import kotlin.collections.set

class ModelModule: SparkPackModule {

    override val id: String = "models"

    override fun onStart(isClientSide: Boolean, fromServer: Boolean) {
        if (fromServer) OModel.ORIGINS.clear()
    }

    override fun read(
        namespace: String,
        pathSegments: List<String>,
        fileName: String,
        content: ByteArray,
        pack: SparkPackage,
        isClientSide: Boolean, fromServer: Boolean
    ) {
        if (!fromServer) return
        if (pathSegments.isEmpty()) throw IllegalArgumentException("模型的文件路径必须指向一个模型父名称（如：minecraft/models/entity/player.json 指向名为 minecraft:player 的entity模型）")
        val json = JsonParser.parseString(String(content, StandardCharsets.UTF_8))

        // 打入GeckoLib支持
        if (isClientSide && SparkPackLoader.isGeckoLib) {
            readGeckoLibBakedGeoModel(namespace,pathSegments, fileName, json)
        }

        val geometryArray = json.asJsonObject.getAsJsonArray("minecraft:geometry")
        val geometry = geometryArray.first().asJsonObject
        val bonesArray = geometry.getAsJsonArray("bones")

        // 单独读取贴图长宽
        val texture = geometry.getAsJsonObject("description")
        val coord = Vector2i(GsonHelper.getAsInt(texture, "texture_width"), GsonHelper.getAsInt(texture, "texture_height"))

        val bones = OBone.MAP_CODEC.decode(JsonOps.INSTANCE, bonesArray).orThrow.first.mapValues { boneEntry ->
            val bone = boneEntry.value

            // 应用长宽和修正到所有方块
            val cubes = bone.cubes.map { cube ->
                OCube(
                    Vec3(-cube.originPos.x - cube.size.x, cube.originPos.y, cube.originPos.z).div(16.0),
                    cube.size.div(16.0),
                    cube.pivot.multiply(-1.0, 1.0, 1.0).div(16.0),
                    cube.rotation.multiply(-1.0, -1.0, 1.0).toRadians(),
                    cube.inflate.div(16),
                    cube.uvUnion,
                    cube.mirror,
                    coord.x,
                    coord.y
                )
            }.toMutableList()
            
            // 解析mesh数据
            val mesh = parseMeshFromBone(boneEntry.key, bonesArray, coord.x, coord.y)
            
            val locators = bone.locators.mapValues { (_, value) ->
                OLocator(
                    value.offset.div(16.0).multiply(-1.0, 1.0, 1.0),
                    value.rotation.multiply(-1.0, -1.0, 1.0).toRadians()
                )
            }
            
            OBone(
                bone.name,
                bone.parentName,
                bone.pivot.multiply(-1.0, 1.0, 1.0).div(16.0),
                bone.rotation.multiply(-1.0, -1.0, 1.0).toRadians(),
                LinkedHashMap(locators),
                ArrayList(cubes),
                mesh
            )
        }
        val id = ResourceLocation.fromNamespaceAndPath(namespace, fileName.removeSuffix(".json"))

        OModel.ORIGINS[ModelIndex(pathSegments[0], id)] = OModel(coord.x, coord.y, LinkedHashMap(bones))
    }

    private fun readGeckoLibBakedGeoModel(
        namespace: String,
        pathSegments: List<String>,
        fileName: String,
        json: JsonElement
    ) {
        val bakedGeoModelMap = GeckoLibCache.getBakedModels()
        if (!fileName.endsWith(".geo.json")) {
            return
        }
        val resourceLocation = location(namespace, pathSegments, fileName)
        try {
            val bakedGeoModel = KeyFramesAdapter.GEO_GSON.fromJson(json.asJsonObject, Model::class.java)

            when (bakedGeoModel.formatVersion()) {
                FormatVersion.V_1_12_0 -> {}
                FormatVersion.V_1_14_0 -> GeckoLibConstants.LOGGER.warn(
                    "Unsupported geometry json version: 1.14.0 for model {}. This model may not appear as expected",
                )

                FormatVersion.V_1_21_0 -> GeckoLibConstants.LOGGER.warn(
                    "Unsupported geometry json version: 1.21.0 for model {}. Supported versions: 1.12.0. Remove any rotated face UVs and re-export the model to fix",
                )

                null -> GeckoLibConstants.LOGGER.warn(
                    "Unsupported geometry json version for model {}. Supported versions: 1.12.0",
                )
            }

            bakedGeoModelMap[resourceLocation] = BakedModelFactory.getForNamespace(resourceLocation.namespace)
                .constructGeoModel(GeometryTree.fromModel(bakedGeoModel))
        } catch (ex: Exception) {
            throw GeckoLibConstants.exception(resourceLocation, "Error loading model file", ex)
        }
    }
    
    /**
     * 从骨骼JSON数据中解析mesh数据
     * 
     * @param boneName 骨骼名称
     * @param bonesArray 骨骼数组JSON
     * @param textureWidth 纹理宽度
     * @param textureHeight 纹理高度
     * @return 解析出的mesh（可为null）
     */
    private fun parseMeshFromBone(
        boneName: String,
        bonesArray: JsonElement,
        textureWidth: Int,
        textureHeight: Int
    ): OMesh? {
        // 查找对应骨骼的JSON数据
        val boneJson = bonesArray.asJsonArray.firstOrNull { bone ->
            bone.asJsonObject.get("name")?.asString == boneName
        } ?: return null
        
        val boneObj = boneJson.asJsonObject
        
        // 检查是否有poly_mesh字段
        val polyMeshElement = boneObj.get("poly_mesh")
        if (polyMeshElement != null && polyMeshElement.isJsonObject) {
            val polyMesh = polyMeshElement.asJsonObject
            
            // 解析mesh数据
            val normalizedUvs = polyMesh.get("normalized_uvs")?.asBoolean ?: true
            val positions = parseFloatArrayArray(polyMesh.get("positions"))
            val normals = parseFloatArrayArray(polyMesh.get("normals"))
            val uvs = parseFloatArrayArray(polyMesh.get("uvs"))
            val polys = parseIntArrayArrayArray(polyMesh.get("polys"))
            
            // 构建OMesh
            return OMesh.fromRawData(
                normalizedUvs = normalizedUvs,
                positions = positions,
                normals = normals,
                uvs = uvs,
                polys = polys,
                textureWidth = textureWidth,
                textureHeight = textureHeight
            )
        }
        
        return null
    }
    
    /**
     * 解析浮点数二维数组 [[x, y, z], ...]
     */
    private fun parseFloatArrayArray(element: JsonElement?): List<List<Float>> {
        if (element == null || !element.isJsonArray) return emptyList()
        
        return element.asJsonArray.map { innerElement ->
            if (innerElement.isJsonArray) {
                innerElement.asJsonArray.map { it.asFloat }
            } else {
                emptyList()
            }
        }
    }
    
    /**
     * 解析整数三维数组 [[[posIdx, normalIdx, uvIdx], ...], ...]
     */
    private fun parseIntArrayArrayArray(element: JsonElement?): List<List<List<Int>>> {
        if (element == null || !element.isJsonArray) return emptyList()
        
        return element.asJsonArray.map { polyElement ->
            if (polyElement.isJsonArray) {
                polyElement.asJsonArray.map { vertexElement ->
                    if (vertexElement.isJsonArray) {
                        vertexElement.asJsonArray.map { it.asInt }
                    } else {
                        emptyList()
                    }
                }
            } else {
                emptyList()
            }
        }
    }

    override fun onFinish(isClientSide: Boolean, fromServer: Boolean) {
        SparkCore.logger("模型加载器").info("\n\uD83D\uDEB6已加载模型\uD83D\uDEB6\n✅${OModel.ORIGINS.map { it.key }}\n")
    }

}