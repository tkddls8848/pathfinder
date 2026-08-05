-keepattributes Signature, InnerClasses, EnclosingMethod
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations

# Retrofit
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation

# Moshi (codegen 이 생성한 JsonAdapter 유지)
-keep class **JsonAdapter { <init>(...); }
-keepnames @com.squareup.moshi.JsonClass class *

# DTO 는 리플렉션 없이 codegen 을 쓰지만, 필드명이 그대로 유지되어야 안전하다.
-keepclassmembers class kr.eodiga.wayfinder.data.remote.dto.** { *; }
