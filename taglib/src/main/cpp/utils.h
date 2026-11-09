#ifndef TAGLIB_UTILS_H
#define TAGLIB_UTILS_H

#include <jni.h>
#include <unistd.h>

#include "fileref_ext.h"
#include "tpropertymap.h"

jclass stringClass = nullptr;

jclass hashMapClass = nullptr;
jmethodID hashMapInit = nullptr;
jmethodID hashMapPut = nullptr;

jclass metadataClass = nullptr;
jmethodID metadataConstructor = nullptr;

jclass audioPropertiesClass = nullptr;
jmethodID audioPropertiesConstructor = nullptr;

jclass pictureClass = nullptr;
jmethodID pictureConstructor = nullptr;
jmethodID pictureGetData = nullptr;
jmethodID pictureGetDescription = nullptr;
jmethodID pictureGetPictureType = nullptr;
jmethodID pictureGetMimeType = nullptr;

jclass entrySetClass = nullptr;
jmethodID iteratorMethod = nullptr;
jmethodID entrySetMethod = nullptr;

jclass iteratorClass = nullptr;
jmethodID hasNextMethod = nullptr;
jmethodID nextMethod = nullptr;

jclass mapEntryClass = nullptr;
jmethodID getKeyMethod = nullptr;
jmethodID getValueMethod = nullptr;

extern "C" JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *) {
    JNIEnv *env;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }

    jclass _stringClass = env->FindClass("java/lang/String");
    stringClass = reinterpret_cast<jclass>(env->NewGlobalRef(_stringClass));
    env->DeleteLocalRef(_stringClass);

    jclass _hashMapClass = env->FindClass("java/util/HashMap");
    hashMapClass = reinterpret_cast<jclass>(env->NewGlobalRef(_hashMapClass));
    env->DeleteLocalRef(_hashMapClass);
    hashMapInit = env->GetMethodID(hashMapClass, "<init>", "(I)V");
    hashMapPut = env->GetMethodID(hashMapClass, "put",
                                  "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;");

    jclass _metadataClass = env->FindClass("com/kyant/taglib/Metadata");
    metadataClass = reinterpret_cast<jclass>(env->NewGlobalRef(_metadataClass));
    env->DeleteLocalRef(_metadataClass);
    metadataConstructor = env->GetMethodID(metadataClass, "<init>",
                                           "(Ljava/util/HashMap;[Lcom/kyant/taglib/Picture;)V");

    jclass _audioPropertiesClass = env->FindClass("com/kyant/taglib/AudioProperties");
    audioPropertiesClass = reinterpret_cast<jclass>(env->NewGlobalRef(_audioPropertiesClass));
    env->DeleteLocalRef(_audioPropertiesClass);
    audioPropertiesConstructor = env->GetMethodID(audioPropertiesClass, "<init>", "(IIII)V");

    jclass _pictureClass = env->FindClass("com/kyant/taglib/Picture");
    pictureClass = reinterpret_cast<jclass>(env->NewGlobalRef(_pictureClass));
    env->DeleteLocalRef(_pictureClass);
    pictureConstructor = env->GetMethodID(pictureClass, "<init>",
                                          "([BLjava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
    pictureGetData = env->GetMethodID(pictureClass, "getData", "()[B");
    pictureGetDescription = env->GetMethodID(pictureClass, "getDescription", "()Ljava/lang/String;");
    pictureGetPictureType = env->GetMethodID(pictureClass, "getPictureType", "()Ljava/lang/String;");
    pictureGetMimeType = env->GetMethodID(pictureClass, "getMimeType", "()Ljava/lang/String;");

    jclass _entrySetClass = env->FindClass("java/util/Set");
    entrySetClass = reinterpret_cast<jclass>(env->NewGlobalRef(_entrySetClass));
    env->DeleteLocalRef(_entrySetClass);
    iteratorMethod = env->GetMethodID(entrySetClass, "iterator", "()Ljava/util/Iterator;");
    entrySetMethod = env->GetMethodID(hashMapClass, "entrySet", "()Ljava/util/Set;");

    jclass _iteratorClass = env->FindClass("java/util/Iterator");
    iteratorClass = reinterpret_cast<jclass>(env->NewGlobalRef(_iteratorClass));
    env->DeleteLocalRef(_iteratorClass);
    hasNextMethod = env->GetMethodID(iteratorClass, "hasNext", "()Z");
    nextMethod = env->GetMethodID(iteratorClass, "next", "()Ljava/lang/Object;");

    jclass _mapEntryClass = env->FindClass("java/util/Map$Entry");
    mapEntryClass = reinterpret_cast<jclass>(env->NewGlobalRef(_mapEntryClass));
    env->DeleteLocalRef(_mapEntryClass);
    getKeyMethod = env->GetMethodID(mapEntryClass, "getKey", "()Ljava/lang/Object;");
    getValueMethod = env->GetMethodID(mapEntryClass, "getValue", "()Ljava/lang/Object;");

    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT void JNI_OnUnload(JavaVM *vm, void *) {
    JNIEnv *env;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return;
    }

    env->DeleteGlobalRef(stringClass);
    env->DeleteGlobalRef(hashMapClass);
    env->DeleteGlobalRef(metadataClass);
    env->DeleteGlobalRef(audioPropertiesClass);
    env->DeleteGlobalRef(pictureClass);
    env->DeleteGlobalRef(entrySetClass);
    env->DeleteGlobalRef(iteratorClass);
    env->DeleteGlobalRef(mapEntryClass);

    stringClass = nullptr;
    hashMapClass = nullptr;
    hashMapInit = nullptr;
    hashMapPut = nullptr;
    metadataClass = nullptr;
    metadataConstructor = nullptr;
    audioPropertiesClass = nullptr;
    audioPropertiesConstructor = nullptr;
    pictureClass = nullptr;
    pictureConstructor = nullptr;
    pictureGetData = nullptr;
    pictureGetDescription = nullptr;
    pictureGetPictureType = nullptr;
    pictureGetMimeType = nullptr;
    entrySetClass = nullptr;
    iteratorMethod = nullptr;
    entrySetMethod = nullptr;
    iteratorClass = nullptr;
    hasNextMethod = nullptr;
    nextMethod = nullptr;
    mapEntryClass = nullptr;
    getKeyMethod = nullptr;
    getValueMethod = nullptr;
}

// KJB patch: strings cross JNI as UTF-16 (NewString, GetStringChars), not as "modified UTF-8", which mangles characters
// outside the BMP (an emoji in a title). Every converter checks for a pending Java exception after each JNI call and
// stops there, returning null or what it has, so no JNI call runs with an exception pending; the caller checks again.

// KJB patch: a TagLib string as a Java string.
jstring StringToJniString(JNIEnv *env, const TagLib::String &string) {
    const TagLib::ByteVector utf16 = string.data(TagLib::String::UTF16LE);
    return env->NewString(reinterpret_cast<const jchar *>(utf16.data()), static_cast<jsize>(utf16.size() / 2));
}

// KJB patch: a Java string as a TagLib string, its characters released.
TagLib::String JniStringToString(JNIEnv *env, jstring string) {
    if (string == nullptr) {
        return {};
    }
    const jsize length = env->GetStringLength(string);
    const jchar *chars = env->GetStringChars(string, nullptr);
    if (chars == nullptr) {
        return {};
    }
    const TagLib::ByteVector utf16(reinterpret_cast<const char *>(chars), static_cast<unsigned int>(length) * 2);
    env->ReleaseStringChars(string, chars);
    return {utf16, TagLib::String::UTF16LE};
}

// Helper function to convert C++ StringList to JNI String array
jobjectArray StringListToJniStringArray(JNIEnv *env, const TagLib::StringList &stringList) {
    jobjectArray array = env->NewObjectArray(static_cast<jsize>(stringList.size()),
                                             stringClass, nullptr);
    if (array == nullptr) {
        return nullptr;
    }
    int i = 0;
    for (const auto &str: stringList) {
        jstring jStr = StringToJniString(env, str);
        if (jStr == nullptr) {
            return nullptr;
        }
        env->SetObjectArrayElement(array, i, jStr);
        env->DeleteLocalRef(jStr);
        i++;
    }
    return array;
}

// Helper function to convert C++ PropertyMap to JNI HashMap
jobject PropertyMapToJniHashMap(JNIEnv *env, const TagLib::PropertyMap &propertyMap) {
    jobject hashMap = env->NewObject(hashMapClass, hashMapInit, static_cast<jint>(propertyMap.size()));
    if (hashMap == nullptr) {
        return nullptr;
    }

    for (const auto &property: propertyMap) {
        jobjectArray valueArray = StringListToJniStringArray(env, property.second);
        if (valueArray == nullptr) {
            return nullptr;
        }
        jstring jKey = StringToJniString(env, property.first);
        if (jKey == nullptr) {
            return nullptr;
        }
        jobject previous = env->CallObjectMethod(hashMap, hashMapPut, jKey, valueArray);
        if (env->ExceptionCheck()) {
            return nullptr;
        }

        env->DeleteLocalRef(previous);
        env->DeleteLocalRef(jKey);
        env->DeleteLocalRef(valueArray);
    }

    return hashMap;
}

// Helper function to convert JNI String array to C++ StringList
TagLib::StringList JniStringArrayToStringList(JNIEnv *env, jobjectArray stringArray) {
    TagLib::StringList stringList;

    const jsize arrayLength = env->GetArrayLength(stringArray);
    for (int i = 0; i < arrayLength; ++i) {
        auto jStr = reinterpret_cast<jstring>(env->GetObjectArrayElement(stringArray, i));
        if (env->ExceptionCheck()) {
            return stringList;
        }
        stringList.append(JniStringToString(env, jStr));
        env->DeleteLocalRef(jStr);
        if (env->ExceptionCheck()) {
            return stringList;
        }
    }

    return stringList;
}

// Helper function to convert JNI HashMap to C++ PropertyMap
TagLib::PropertyMap JniHashMapToPropertyMap(JNIEnv *env, jobject hashMap) {
    TagLib::PropertyMap propertyMap;

    jobject entrySet = env->CallObjectMethod(hashMap, entrySetMethod);
    if (env->ExceptionCheck() || entrySet == nullptr) {
        return propertyMap;
    }
    jobject iterator = env->CallObjectMethod(entrySet, iteratorMethod);
    if (env->ExceptionCheck() || iterator == nullptr) {
        return propertyMap;
    }

    while (env->CallBooleanMethod(iterator, hasNextMethod)) {
        if (env->ExceptionCheck()) {
            return propertyMap;
        }
        jobject entry = env->CallObjectMethod(iterator, nextMethod);
        if (env->ExceptionCheck()) {
            return propertyMap;
        }
        jobject key = env->CallObjectMethod(entry, getKeyMethod);
        if (env->ExceptionCheck()) {
            return propertyMap;
        }
        jobject value = env->CallObjectMethod(entry, getValueMethod);
        if (env->ExceptionCheck()) {
            return propertyMap;
        }

        const TagLib::String keyString = JniStringToString(env, reinterpret_cast<jstring>(key));
        const StringList valueList = JniStringArrayToStringList(env, reinterpret_cast<jobjectArray>(value));
        if (env->ExceptionCheck()) {
            return propertyMap;
        }
        propertyMap[keyString] = valueList;

        env->DeleteLocalRef(entry);
        env->DeleteLocalRef(key);
        env->DeleteLocalRef(value);
    }
    env->DeleteLocalRef(iterator);
    env->DeleteLocalRef(entrySet);

    return propertyMap;
}

// Helper function to convert C++ PictureList to JNI Picture array
jobjectArray PictureListToJniPictureArray(
        JNIEnv *env,
        const TagLib::List<TagLib::Map<TagLib::String, TagLib::Variant>> &pictureList
) {
    // KJB patch: pictures without data are left out, so the array is sized to the rest (no null at its end).
    jsize count = 0;
    for (const auto &picture: pictureList) {
        if (!picture["data"].toByteVector().isEmpty()) {
            count++;
        }
    }
    jobjectArray array = env->NewObjectArray(count, pictureClass, nullptr);
    if (array == nullptr) {
        return nullptr;
    }
    int i = 0;
    for (const auto &picture: pictureList) {
        const ByteVector pictureData = picture["data"].toByteVector();
        if (pictureData.isEmpty()) {
            continue;
        }

        jbyteArray bytes = env->NewByteArray(static_cast<jint>(pictureData.size()));
        if (bytes == nullptr) {
            return nullptr;
        }
        env->SetByteArrayRegion(
                bytes,
                0,
                static_cast<jint>(pictureData.size()),
                reinterpret_cast<const jbyte *>(pictureData.data())
        );
        jstring jDescription = StringToJniString(env, picture["description"].toString());
        jstring jPictureType = StringToJniString(env, picture["pictureType"].toString());
        jstring jMimeType = StringToJniString(env, picture["mimeType"].toString());
        if (env->ExceptionCheck()) {
            return nullptr;
        }
        jobject pictureObject = env->NewObject(
                pictureClass, pictureConstructor,
                bytes, jDescription, jPictureType, jMimeType);
        if (pictureObject == nullptr) {
            return nullptr;
        }
        env->DeleteLocalRef(bytes);
        env->DeleteLocalRef(jDescription);
        env->DeleteLocalRef(jPictureType);
        env->DeleteLocalRef(jMimeType);
        env->SetObjectArrayElement(array, i, pictureObject);
        env->DeleteLocalRef(pictureObject);
        i++;
    }
    return array;
}

// Helper function to convert JNI Picture array to C++ PictureList
TagLib::List<TagLib::Map<TagLib::String, TagLib::Variant>>
JniPictureArrayToPictureList(JNIEnv *env, jobjectArray pictures) {
    TagLib::List<TagLib::Map<TagLib::String, TagLib::Variant>> pictureList;

    const jsize pictureCount = env->GetArrayLength(pictures);
    for (int i = 0; i < pictureCount; i++) {
        jobject pictureObject = env->GetObjectArrayElement(pictures, i);
        if (env->ExceptionCheck() || pictureObject == nullptr) {
            return pictureList;
        }
        const auto bytes = reinterpret_cast<jbyteArray>(env->CallObjectMethod(pictureObject, pictureGetData));
        if (env->ExceptionCheck() || bytes == nullptr) {
            return pictureList;
        }
        const auto description = reinterpret_cast<jstring>(env->CallObjectMethod(pictureObject, pictureGetDescription));
        if (env->ExceptionCheck()) {
            return pictureList;
        }
        const auto pictureType = reinterpret_cast<jstring>(env->CallObjectMethod(pictureObject, pictureGetPictureType));
        if (env->ExceptionCheck()) {
            return pictureList;
        }
        const auto mimeType = reinterpret_cast<jstring>(env->CallObjectMethod(pictureObject, pictureGetMimeType));
        if (env->ExceptionCheck()) {
            return pictureList;
        }

        jbyte *pictureData = env->GetByteArrayElements(bytes, nullptr);
        if (pictureData == nullptr) {
            return pictureList;
        }
        const jsize pictureDataSize = env->GetArrayLength(bytes);
        TagLib::ByteVector pictureDataVector(
                reinterpret_cast<const char *>(pictureData),
                static_cast<uint>(pictureDataSize)
        );
        env->ReleaseByteArrayElements(bytes, pictureData, JNI_ABORT);

        // KJB patch: the strings are read as UTF-16 and released, and the local references freed in the loop.
        TagLib::Map<TagLib::String, TagLib::Variant> picture;
        picture["data"] = pictureDataVector;
        picture["description"] = JniStringToString(env, description);
        picture["pictureType"] = JniStringToString(env, pictureType);
        picture["mimeType"] = JniStringToString(env, mimeType);
        if (env->ExceptionCheck()) {
            return pictureList;
        }
        pictureList.append(picture);
        env->DeleteLocalRef(bytes);
        env->DeleteLocalRef(description);
        env->DeleteLocalRef(pictureType);
        env->DeleteLocalRef(mimeType);
        env->DeleteLocalRef(pictureObject);
    }

    return pictureList;
}

jobject getAudioProperties(JNIEnv *env, const TagLibExt::FileRef &f) {
    const AudioProperties *audioProperties = f.audioProperties();
    if (audioProperties) {
        const jint duration = static_cast<jint>(audioProperties->lengthInMilliseconds());
        const jint bitrate = static_cast<jint>(audioProperties->bitrate());
        const jint sampleRate = static_cast<jint>(audioProperties->sampleRate());
        const jint channels = static_cast<jint>(audioProperties->channels());
        return env->NewObject(
                audioPropertiesClass, audioPropertiesConstructor,
                duration, bitrate, sampleRate, channels);
    }
    return env->NewObject(audioPropertiesClass, audioPropertiesConstructor, 0, 0, 0, 0);
}

jobject getPropertyMap(JNIEnv *env, const TagLibExt::FileRef &f) {
    return PropertyMapToJniHashMap(env, f.properties());
}

jobjectArray getPictures(JNIEnv *env, const TagLibExt::FileRef &f) {
    return PictureListToJniPictureArray(env, f.complexProperties("PICTURE"));
}

jobjectArray emptyPictureArray(JNIEnv *env) {
    return env->NewObjectArray(0, pictureClass, nullptr);
}

char *getRealPathFromFd(const int fd) {
    char path[22];
    if (snprintf(path, sizeof(path), "/proc/self/fd/%d", fd) < 0) {
        return nullptr;
    }

    size_t size = 128;
    char *link = reinterpret_cast<char *>(malloc(size));
    if (link == nullptr) {
        return nullptr;
    }

    ssize_t bytesRead;
    while ((bytesRead = readlink(path, link, size)) == static_cast<ssize_t>(size)) {
        size *= 2;
        char *temp = reinterpret_cast<char *>(realloc(link, size));
        if (temp == nullptr) {
            free(link);
            return nullptr;
        }
        link = temp;
    }

    // KJB patch: readlink failing (-1) was used as an index.
    if (bytesRead < 0) {
        free(link);
        return nullptr;
    }
    link[bytesRead] = '\0';

    return link;
}

#endif //TAGLIB_UTILS_H
