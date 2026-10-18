package one.njk.sao.extension.local

/** Pinned encoder pairs. Embeddings from different pairs are never compared. */
data class ClipModel(val id: String,val label: Int,val base: String,val parts: List<ClipModels.Part>,
    val visual: String,val textual: String,val tokenizer: String,val quantized: Boolean=false) {
    val bytes get()=parts.sumOf { it.size }
}
object ClipCatalog {
    val immich=ClipModel(ClipModels.MODEL,R.string.model_immich,
        "https://huggingface.co/immich-app/ViT-B-32__openai/resolve/a857c8de2c07bbcfa6646adfcf31b798845afa1e/",
        listOf(
            ClipModels.Part("visual/model.onnx",351613724,"33a3df41ceef21acdf371af00f6dd0456ec1f9eba24d03a7720f9c3734e40859"),
            ClipModels.Part("textual/model.onnx",254193396,"b80cf0af751533a6712d92247f0ddc0c95208748bc59a1a27f33e67be6864e3b"),
            ClipModels.Part("textual/tokenizer.json",3642073,"6d9109cc838977f3ca94a379eec36aecc7c807e1785cd729660ca2fc0171fb35")),
        "visual/model.onnx","textual/model.onnx","textual/tokenizer.json")
    val lite=ClipModel("ViT-B-32__openai-uint8@d15189d7028b43f1d3e65039190477f6af591c2a",R.string.model_lite,
        "https://huggingface.co/Xenova/clip-vit-base-patch32/resolve/d15189d7028b43f1d3e65039190477f6af591c2a/",
        listOf(
            ClipModels.Part("onnx/vision_model_uint8.onnx",88648915,"b95448754a6ae56964ec80c570c80bb9863787ee85f51b664abed8c0e5f22a7a"),
            ClipModels.Part("onnx/text_model_uint8.onnx",64070835,"cbe64fd9eaa6324d49006ce99c104107fdf6b26d25bebff96ecbb5d8a24c5e54"),
            ClipModels.Part("tokenizer.json",2224119,"f7f3b7af117d467b58374797691a6438d3e6b9e9cef800dfd5dced7f697a90cd")),
        "onnx/vision_model_uint8.onnx","onnx/text_model_uint8.onnx","tokenizer.json",true)
    val all=listOf(immich,lite)
    fun get(id: String)=all.single { it.id==id }
}
