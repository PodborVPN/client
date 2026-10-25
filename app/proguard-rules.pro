# Правила R8 для релиза. Библиотеки со своими правилами (kotlinx.serialization, Compose, Arrow, libXray — `go.**`,
# `libXray.**`) их приносят сами; здесь — то, что держится рефлексией и правил не несёт.

# API Xray: сообщения proto (полный protobuf, не lite) читаются и пишутся рефлексией по дескрипторам — держим
# сгенерированные классы целиком.
-keep class com.xray.** { *; }
-keep class * extends com.google.protobuf.GeneratedMessage { *; }
-keep class * extends com.google.protobuf.GeneratedMessage$Builder { *; }

# gRPC на Unix-сокете: UdsChannelBuilder (grpc-android) создаёт OkHttpChannelBuilder рефлексией — `forTarget` и
# сеттеры по имени; без правила R8 их вырезает, и служба падает на первом канале к API Xray.
-keep class io.grpc.okhttp.OkHttpChannelBuilder { public *; }

# gRPC: транспорты и балансировщики находятся через ServiceLoader; классы, которых на Android нет (netty, alts,
# java.lang.management), — не ошибка сборки.
-dontwarn io.grpc.**
-dontwarn com.google.protobuf.**
-dontwarn javax.naming.**
-dontwarn java.lang.management.**
-dontwarn org.codehaus.mojo.animal_sniffer.**
-dontwarn com.google.errorprone.annotations.**
-dontwarn com.google.j2objc.annotations.**
