package com.kyant.taglib

/**
 * An object that provides access to the native TagLib library.
 */
public object TagLib {

    init {
        System.loadLibrary("taglib")
    }

    @JvmStatic
    private external fun getAudioProperties(
        fd: Int,
        readStyle: Int,
    ): AudioProperties?

    /**
     * Get audio properties from file descriptor.
     *
     * @param fd File descriptor
     * @param readStyle Read style for audio properties to balance speed and accuracy
     */
    @JvmStatic
    public fun getAudioProperties(
        fd: Int,
        readStyle: AudioPropertiesReadStyle = AudioPropertiesReadStyle.Average,
    ): AudioProperties? = getAudioProperties(fd, readStyle.ordinal)

    /**
     * Get metadata from file descriptor.
     *
     * @param fd File descriptor
     * @param readPictures Whether to read pictures
     */
    @JvmStatic
    public external fun getMetadata(
        fd: Int,
        readPictures: Boolean = true,
    ): Metadata?

    /**
     * Get metadata property values from file descriptor.
     *
     * @param fd File descriptor
     * @param propertyName Property name
     */
    @JvmStatic
    public external fun getMetadataPropertyValues(
        fd: Int,
        propertyName: String,
    ): Array<String>?

    /**
     * Get pictures from file descriptor. There may be multiple pictures with different types.
     */
    @JvmStatic
    public external fun getPictures(fd: Int): Array<Picture>

    /**
     * Get front cover from file descriptor.
     */
    @JvmStatic
    public fun getFrontCover(fd: Int): Picture? {
        val pictures = getPictures(fd)
        return pictures.find { picture -> picture.pictureType == "Front Cover" }
            ?: pictures.firstOrNull()
    }

    /**
     * Save metadata by file descriptor.
     *
     * @param fd File descriptor
     * @param propertyMap Property map to save
     *
     * @return Whether the operation was successful
     */
    @JvmStatic
    public external fun savePropertyMap(
        fd: Int,
        propertyMap: PropertyMap,
    ): Boolean

    /**
     * Save pictures by file descriptor.
     *
     * @param fd File descriptor
     * @param pictures Pictures to save
     *
     * @return Whether the operation was successful
     */
    @JvmStatic
    public external fun savePictures(
        fd: Int,
        pictures: Array<Picture>,
    ): Boolean

    /**
     * KJB patch: saves the whole tag in one go, every key of [propertyMap], and [pictures] when given (null leaves the
     * pictures as they are). Keys left out are removed, so read them first. An MP3 has only its ID3v2 tag rewritten, as
     * version [id3v2Version] (3 or 4): no ID3v1 tag is added, and one already there is left as it is; an APE tag already
     * there gets the same changes.
     *
     * @param fd File descriptor, closed by this call on every path
     *
     * @return Whether the operation was successful
     */
    @JvmStatic
    public external fun saveTags(
        fd: Int,
        propertyMap: PropertyMap,
        pictures: Array<Picture>?,
        id3v2Version: Int,
    ): Boolean

    /**
     * KJB patch: an MP3's ID3v2 tag as its major version ("3" or "4") followed by the ID of every frame in it, in order;
     * null for a file that is not an MP3 or has no ID3v2 tag.
     *
     * @param fd File descriptor, closed by this call on every path
     */
    @JvmStatic
    public external fun getId3v2Frames(fd: Int): Array<String>?
}
