# Ktor, kotlinx.serialization and cryptography-kotlin ship their own consumer rules. These quiet the optional
# server/JVM classes Ktor references but Android does not have.
-dontwarn java.lang.management.**
-dontwarn org.slf4j.impl.**
-dontwarn javax.naming.**
-dontwarn io.netty.**
-dontwarn reactor.blockhound.**
