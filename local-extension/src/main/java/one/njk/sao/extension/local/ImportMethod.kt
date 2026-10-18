package one.njk.sao.extension.local

/** Only pickers with persistable read access are offered for a durable library. */
enum class ImportMethod(val label: Int,val help: Int,val action: Int) {
    FOLDER(R.string.picker_folder,R.string.picker_folder_help,R.string.add_folder),
    PHOTOS(R.string.picker_photos,R.string.picker_photos_help,R.string.add_media),
    FILES(R.string.picker_files,R.string.picker_files_help,R.string.add_media)
}
