# KJB release shrinking rules.
#
# The activity and the notification listener are referenced from the manifest, which AGP
# keeps automatically. kotlinx.serialization, DataStore, coroutines and Compose ship their
# own consumer rules. KJB + AI (the full edition) adds proguard-ai.pro for ONNX Runtime.
#
# Add -keep rules here if a future change loads classes by name.
