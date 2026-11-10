#include "tfilestream.h"
#include "apetag.h"
#include "id3v2frame.h"
#include "id3v2header.h"
#include "id3v2tag.h"
#include "mpegfile.h"
#include "utils.h"

// KJB patch: every call goes through withFile. The descriptor is the call's own and is closed on every path (also when
// its path can't be read or no stream opens on it), a C++ exception never crosses into Java (the call returns its
// fallback), and a Java exception raised meanwhile is left pending for the caller, with the fallback returned.
template<typename T, typename Body>
static T withFile(JNIEnv *env, jint fd, bool readOnly, T fallback, Body body) {
    char *path = getRealPathFromFd(fd);
    if (path == nullptr) {
        close(fd);
        return fallback;
    }
    try {
        const auto stream = std::make_unique<TagLib::FileStream>(fd, readOnly);
        if (!stream->isOpen()) {
            free(path);
            close(fd);
            return fallback;
        }
        T result = body(path, stream.get());
        free(path);
        return env->ExceptionCheck() ? fallback : result;
    } catch (...) {
        free(path);
        return fallback;
    }
}

extern "C" {
JNIEXPORT jobject JNICALL
Java_com_kyant_taglib_TagLib_getAudioProperties(
        JNIEnv *env,
        jclass,
        jint fd,
        jint read_style
) {
    return withFile<jobject>(env, fd, true, nullptr, [&](const char *path, TagLib::FileStream *stream) -> jobject {
        const auto style = static_cast<TagLib::AudioProperties::ReadStyle>(read_style);
        const TagLibExt::FileRef f(path, stream, true, style);
        if (f.isNull()) {
            return nullptr;
        }
        return getAudioProperties(env, f);
    });
}

JNIEXPORT jobject JNICALL
Java_com_kyant_taglib_TagLib_getMetadata(
        JNIEnv *env,
        jclass,
        jint fd,
        jboolean read_pictures
) {
    return withFile<jobject>(env, fd, true, nullptr, [&](const char *path, TagLib::FileStream *stream) -> jobject {
        const TagLibExt::FileRef f(path, stream, false);
        if (f.isNull()) {
            return nullptr;
        }
        jobject propertiesMap = getPropertyMap(env, f);
        if (propertiesMap == nullptr || env->ExceptionCheck()) {
            return nullptr;
        }
        jobjectArray pictures = read_pictures ? getPictures(env, f) : emptyPictureArray(env);
        if (pictures == nullptr || env->ExceptionCheck()) {
            return nullptr;
        }
        return env->NewObject(metadataClass, metadataConstructor, propertiesMap, pictures);
    });
}

JNIEXPORT jobjectArray JNICALL
Java_com_kyant_taglib_TagLib_getMetadataPropertyValues(
        JNIEnv *env,
        jclass,
        jint fd,
        jstring property_name
) {
    return withFile<jobjectArray>(env, fd, true, nullptr, [&](const char *path, TagLib::FileStream *stream) -> jobjectArray {
        const TagLib::String propertyName = JniStringToString(env, property_name);
        if (env->ExceptionCheck()) {
            return nullptr;
        }
        const TagLibExt::FileRef f(path, stream, false);
        if (f.isNull()) {
            return nullptr;
        }
        const auto propertyMap = f.properties();
        const auto valueList = propertyMap.find(propertyName);
        if (valueList == propertyMap.end()) {
            return env->NewObjectArray(0, stringClass, nullptr);
        }
        return StringListToJniStringArray(env, valueList->second);
    });
}

JNIEXPORT jobjectArray JNICALL
Java_com_kyant_taglib_TagLib_getPictures(
        JNIEnv *env,
        jclass,
        jint fd
) {
    return withFile<jobjectArray>(env, fd, true, nullptr, [&](const char *path, TagLib::FileStream *stream) -> jobjectArray {
        const TagLibExt::FileRef f(path, stream, false);
        if (f.isNull()) {
            return emptyPictureArray(env);
        }
        return getPictures(env, f);
    });
}

JNIEXPORT jboolean JNICALL
Java_com_kyant_taglib_TagLib_savePropertyMap(
        JNIEnv *env,
        jclass,
        jint fd,
        jobject property_map
) {
    return withFile<jboolean>(env, fd, false, JNI_FALSE, [&](const char *path, TagLib::FileStream *stream) -> jboolean {
        TagLibExt::FileRef f(path, stream, false);
        if (f.isNull()) {
            return JNI_FALSE;
        }
        const PropertyMap propertyMap = JniHashMapToPropertyMap(env, property_map);
        if (env->ExceptionCheck()) {
            return JNI_FALSE;
        }
        f.setProperties(propertyMap);
        return f.save() ? JNI_TRUE : JNI_FALSE;
    });
}

JNIEXPORT jboolean JNICALL
Java_com_kyant_taglib_TagLib_savePictures(
        JNIEnv *env,
        jclass,
        jint fd,
        jobjectArray pictures
) {
    return withFile<jboolean>(env, fd, false, JNI_FALSE, [&](const char *path, TagLib::FileStream *stream) -> jboolean {
        TagLibExt::FileRef f(path, stream, false);
        if (f.isNull()) {
            return JNI_FALSE;
        }
        auto pictureList = JniPictureArrayToPictureList(env, pictures);
        if (env->ExceptionCheck()) {
            return JNI_FALSE;
        }
        f.setComplexProperties("PICTURE", pictureList);
        return f.save() ? JNI_TRUE : JNI_FALSE;
    });
}

// KJB patch: a picture passed back unchanged keeps every detail the file had for it (FLAC and Ogg also store its width,
// height, colour depth and number of colours, which the Java side does not carry).
static void keepPictureDetails(TagLib::List<TagLib::VariantMap> &pictures, const TagLib::List<TagLib::VariantMap> &existing) {
    for (auto &picture: pictures) {
        for (const auto &old: existing) {
            if (old.value("data").toByteVector() == picture.value("data").toByteVector() &&
                old.value("pictureType").toString() == picture.value("pictureType").toString() &&
                old.value("description").toString() == picture.value("description").toString() &&
                old.value("mimeType").toString() == picture.value("mimeType").toString()) {
                picture = old;
                break;
            }
        }
    }
}

// KJB patch: one save for the whole tag, the property map and the pictures together. Keys left out of the map are
// removed, so the caller reads them first; with no pictures (null) the pictures are left as they are, else they are
// replaced by these. An MP3 gets only its ID3v2 tag rewritten, as version id3v2_version (3 or 4), with DoNotDuplicate:
// no ID3v1 tag is added and one already there is left as it is. An APE tag already there gets the same changes; none
// is added.
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
        return JNI_FALSE;
    }
    return withFile<jboolean>(env, fd, false, JNI_FALSE, [&](const char *path, TagLib::FileStream *stream) -> jboolean {
        TagLibExt::FileRef f(path, stream, false);
        if (f.isNull()) {
            return JNI_FALSE;
        }
        const PropertyMap propertyMap = JniHashMapToPropertyMap(env, property_map);
        if (env->ExceptionCheck()) {
            return JNI_FALSE;
        }
        TagLib::List<TagLib::VariantMap> pictureList;
        if (pictures != nullptr) {
            pictureList = JniPictureArrayToPictureList(env, pictures);
            if (env->ExceptionCheck()) {
                return JNI_FALSE;
            }
            keepPictureDetails(pictureList, f.complexProperties("PICTURE"));
        }

        if (auto *mpeg = dynamic_cast<TagLib::MPEG::File *>(f.file())) {
            TagLib::ID3v2::Tag *tag = mpeg->ID3v2Tag(true);
            const PropertyMap before = tag->properties();
            tag->setProperties(propertyMap);
            if (pictures != nullptr) {
                tag->setComplexProperties("PICTURE", pictureList);
            }
            int tags = TagLib::MPEG::File::ID3v2;
            if (mpeg->hasAPETag()) {
                // Only the keys whose values changed, so the APE tag's own other items stay.
                PropertyMap ape = mpeg->APETag()->properties();
                TagLib::StringList keys;
                for (const auto &[key, values]: before) keys.append(key);
                for (const auto &[key, values]: propertyMap) if (!keys.contains(key)) keys.append(key);
                for (const auto &key: keys) {
                    const auto now = propertyMap.find(key);
                    const auto was = before.find(key);
                    const bool same = (now == propertyMap.end()) == (was == before.end()) &&
                                      (now == propertyMap.end() || now->second == was->second);
                    if (same) continue;
                    if (now == propertyMap.end()) ape.erase(key); else ape.replace(key, now->second);
                }
                mpeg->APETag()->setProperties(ape);
                tags |= TagLib::MPEG::File::APE;
            }
            const auto version = id3v2_version == 3 ? TagLib::ID3v2::v3 : TagLib::ID3v2::v4;
            return mpeg->save(tags, TagLib::File::StripNone, version, TagLib::File::DoNotDuplicate) ? JNI_TRUE : JNI_FALSE;
        }
        f.setProperties(propertyMap);
        if (pictures != nullptr) {
            f.setComplexProperties("PICTURE", pictureList);
        }
        return f.save() ? JNI_TRUE : JNI_FALSE;
    });
}

// KJB patch: an MP3's ID3v2 tag as its major version ("3" or "4") followed by the ID of every frame in it, in order;
// null for a file that is not an MP3 or has no ID3v2 tag. Frames the property map can't show (RVA2, EQU2, PRIV, ...)
// are listed too, so a write that drops one is caught.
JNIEXPORT jobjectArray JNICALL
Java_com_kyant_taglib_TagLib_getId3v2Frames(
        JNIEnv *env,
        jclass,
        jint fd
) {
    return withFile<jobjectArray>(env, fd, true, nullptr, [&](const char *path, TagLib::FileStream *stream) -> jobjectArray {
        TagLibExt::FileRef f(path, stream, false);
        auto *mpeg = f.isNull() ? nullptr : dynamic_cast<TagLib::MPEG::File *>(f.file());
        if (mpeg == nullptr || !mpeg->hasID3v2Tag()) {
            return nullptr;
        }
        TagLib::ID3v2::Tag *tag = mpeg->ID3v2Tag();
        TagLib::StringList ids;
        ids.append(TagLib::String::number(static_cast<int>(tag->header()->majorVersion())));
        for (const auto &frame: tag->frameList()) {
            ids.append(TagLib::String(frame->frameID(), TagLib::String::Latin1));
        }
        return StringListToJniStringArray(env, ids);
    });
}
}
