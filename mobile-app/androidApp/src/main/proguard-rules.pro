# Reglas de ProGuard del punto de entrada Android.
#
# NO SE MINIFICA EN RELEASE (isMinifyEnabled = false) todavia: la app depende de
# Compose Multiplatform y de ML Kit, y activar el minificado sin probarlo en un
# dispositivo real es la forma mas rapida de romper el escaner en produccion sin
# que ningun test lo detecte. Cuando se active, habra que probar el escaner en un
# APK de release en un dispositivo de bodega antes de publicarlo.
