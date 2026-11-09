#include "tfilestream.h"
#include "id3v2tag.h"
#include "mpegfile.h"
#include "utils.h"

extern "C" {
JNIEXPORT jobject JNICALL
Java_com_kyant_taglib_TagLib_getAudioProperties(
        JNIEnv *env,
        jclass,
        jint fd,
        jint read_style
) {
    char *path = getRealPathFromFd(fd);
    if (path == nullptr) {
        return nullptr;
    }
    const auto stream = std::make_unique<TagLib::FileStream>(fd, true);
    const auto style = static_cast<TagLib::AudioProperties::ReadStyle>(read_style);
    const TagLibExt::FileRef f(path, stream.get(), true, style);

    if (f.isNull()) {
        free(path);
        return nullptr;
    }

    jobject audioProperties = getAudioProperties(env, f);
    free(path);
    return audioProperties;
}

JNIEXPORT jobject JNICALL
Java_com_kyant_taglib_TagLib_getMetadata(
        JNIEnv *env,
        jclass,
        jint fd,
        jboolean read_pictures
) {
    char *path = getRealPathFromFd(fd);
    if (path == nullptr) {
        return nullptr;
    }
    const auto stream = std::make_unique<TagLib::FileStream>(fd, true);
    const TagLibExt::FileRef f(path, stream.get(), false);

    if (f.isNull()) {
        free(path);
        return nullptr;
    }

    jobject propertiesMap = getPropertyMap(env, f);
    jobjectArray pictures;
    if (read_pictures) {
        pictures = getPictures(env, f);
    } else {
        pictures = emptyPictureArray(env);
    }

    jobject metadata = env->NewObject(
            metadataClass, metadataConstructor,
            propertiesMap, pictures
    );
    free(path);
    return metadata;
}

JNIEXPORT jobjectArray JNICALL
Java_com_kyant_taglib_TagLib_getMetadataPropertyValues(
        JNIEnv *env,
        jclass,
        jint fd,
        jstring property_name
) {
    const char *propertyName = env->GetStringUTFChars(property_name, nullptr);
    if (propertyName == nullptr) {
        return nullptr;
    }

    char *path = getRealPathFromFd(fd);
    if (path == nullptr) {
        env->ReleaseStringUTFChars(property_name, propertyName);
        return nullptr;
    }
    const auto stream = std::make_unique<TagLib::FileStream>(fd, true);
    const TagLibExt::FileRef f(path, stream.get(), false);

    if (f.isNull()) {
        free(path);
        return nullptr;
    }

    const auto propertyMap = f.properties();
    const auto valueList = propertyMap.find(TagLib::String(propertyName));
    if (valueList == propertyMap.end()) {
        env->ReleaseStringUTFChars(property_name, propertyName);
        free(path);
        return env->NewObjectArray(0, stringClass, nullptr);
    }

    jobjectArray result = env->NewObjectArray(static_cast<jsize>(valueList->second.size()),
                                              stringClass, nullptr);
    int i = 0;
    for (const auto &value: valueList->second) {
        jstring jValue = env->NewStringUTF(value.toCString(true));
        env->SetObjectArrayElement(result, i, jValue);
        env->DeleteLocalRef(jValue);
        i++;
    }

    env->ReleaseStringUTFChars(property_name, propertyName);
    free(path);
    return result;
}

JNIEXPORT jobjectArray JNICALL
Java_com_kyant_taglib_TagLib_getPictures(
        JNIEnv *env,
        jclass,
        jint fd
) {
    char *path = getRealPathFromFd(fd);
    if (path == nullptr) {
        return nullptr;
    }
    const auto stream = std::make_unique<TagLib::FileStream>(fd, true);
    const TagLibExt::FileRef f(path, stream.get(), false);

    if (f.isNull()) {
        free(path);
        return emptyPictureArray(env);
    }

    jobjectArray pictures = getPictures(env, f);
    free(path);
    return pictures;
}

JNIEXPORT jboolean JNICALL
Java_com_kyant_taglib_TagLib_savePropertyMap(
        JNIEnv *env,
        jclass,
        jint fd,
        jobject property_map
) {
    char *path = getRealPathFromFd(fd);
    if (path == nullptr) {
        return false;
    }
    const auto stream = std::make_unique<TagLib::FileStream>(fd, false);
    TagLibExt::FileRef f(path, stream.get(), false);

    if (f.isNull()) {
        free(path);
        return false;
    }

    const PropertyMap propertyMap = JniHashMapToPropertyMap(env, property_map);
    f.setProperties(propertyMap);
    const bool success = f.save();
    free(path);
    return success;
}

JNIEXPORT jboolean JNICALL
Java_com_kyant_taglib_TagLib_savePictures(
        JNIEnv *env,
        jclass,
        jint fd,
        jobjectArray pictures
) {
    char *path = getRealPathFromFd(fd);
    if (path == nullptr) {
        return false;
    }
    const auto stream = std::make_unique<TagLib::FileStream>(fd, false);
    TagLibExt::FileRef f(path, stream.get(), false);

    if (f.isNull()) {
        free(path);
        return false;
    }

    auto pictureList = JniPictureArrayToPictureList(env, pictures);
    f.setComplexProperties("PICTURE", pictureList);
    const bool success = f.save();
    free(path);
    return success;
}

// KJB patch: one save for the whole tag, the property map and the pictures together. Keys and pictures left out are
// removed, so the caller reads them first. An MP3 gets only its ID3v2 tag rewritten, as version id3v2_version (3 or
// 4): no ID3v1 tag is added, and one already there is left as it is, as is an APE tag. The descriptor is closed here
// on every path.
JNIEXPORT jboolean JNICALL
Java_com_kyant_taglib_TagLib_saveTags(
        JNIEnv *env,
        jclass,
        jint fd,
        jobject property_map,
        jobjectArray pictures,
        jint id3v2_version
) {
    if (id3v2_version != 3 && id3v2_version != 4) {
        close(fd);
        return false;
    }
    char *path = getRealPathFromFd(fd);
    if (path == nullptr) {
        close(fd);
        return false;
    }
    const auto stream = std::make_unique<TagLib::FileStream>(fd, false);
    TagLibExt::FileRef f(path, stream.get(), false);
    free(path);

    if (f.isNull()) {
        return false;
    }

    const PropertyMap propertyMap = JniHashMapToPropertyMap(env, property_map);
    const auto pictureList = JniPictureArrayToPictureList(env, pictures);
    if (auto *mpeg = dynamic_cast<TagLib::MPEG::File *>(f.file())) {
        TagLib::ID3v2::Tag *tag = mpeg->ID3v2Tag(true);
        tag->setProperties(propertyMap);
        tag->setComplexProperties("PICTURE", pictureList);
        const auto version = id3v2_version == 3 ? TagLib::ID3v2::v3 : TagLib::ID3v2::v4;
        return mpeg->save(TagLib::MPEG::File::ID3v2, TagLib::File::StripNone, version, TagLib::File::DoNotDuplicate);
    }
    f.setProperties(propertyMap);
    f.setComplexProperties("PICTURE", pictureList);
    return f.save();
}
}
