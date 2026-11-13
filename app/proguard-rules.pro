# KJB release shrinking rules.
#
# The activity and the notification listener are referenced from the manifest, which AGP
# keeps automatically. kotlinx.serialization, DataStore, coroutines and Compose ship their
# own consumer rules. The AI (ONNX Runtime and its reflection) is in the AI add-on, with its
# own rules (ai-addon/proguard-rules.pro).
#
# Add -keep rules here if a future change loads classes by name.
