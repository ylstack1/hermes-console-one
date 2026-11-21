// Adaptador de plataforma para el motor local de Hermes (opcional).
//
// Usa el MethodChannel "hermes/local_engine" para comunicarse con el
// runtime Kotlin que gestiona PRoot + Ubuntu rootfs + Hermes Agent dentro
// de la app, sin necesidad de Termux ni VPS.
//
// Todo es opcional: el usuario activa el interruptor "Motor local Hermes"
// en Ajustes y la app descarga/inicia el runtime bajo demanda.
import 'dart:async';
import 'package:flutter/services.dart';

import '../models/connection.dart';
import 'connection_manager.dart';

/// Estado del motor local Hermes.
enum LocalEngineState {
  /// No se ha instalado nada todavía.
  notInstalled,
  /// PRoot + rootfs están listos, pero Python aún no se ha instalado.
  prootReady,
  /// Python instalado dentro del rootfs.
  pythonReady,
  /// Hermes Agent instalado (venv + CLI).
  hermesReady,
  /// El gateway de Hermes está corriendo en localhost:9119.
  running,
  /// Hubo un error durante la instalación o el arranque.
  error,
}

/// Resultado de la instalación del motor local.
class LocalEngineResult {
  final bool ok;
  final String? message;

  const LocalEngineResult({required this.ok, this.message});
}

/// Contrato de operaciones del motor local (mockeable en tests).
abstract class LocalEngineBridge {
  /// Si la CPU es arm64-v8a (requisito obligatorio para PRoot + rootfs).
  Future<bool> get isArm64;

  /// Si PRoot + rootfs están instalados.
  Future<bool> get isProotReady;

  /// Si Python está instalado dentro del rootfs.
  Future<bool> get isPythonInstalled;

  /// Si Hermes Agent está instalado.
  Future<bool> get isHermesInstalled;

  /// Si el gateway de Hermes está corriendo ahora.
  Future<bool> get isHermesRunning;

  /// Estado actual del motor.
  Future<LocalEngineState> get state;

  /// Ejecuta la instalación completa: rootfs → Python → Hermes Agent.
  /// [onProgress] recibe mensajes de progreso para mostrar en la UI.
  Future<LocalEngineResult> installFull({void Function(String)? onProgress});

  /// Inicia el gateway de Hermes en localhost:9119.
  Future<bool> startHermes();

  /// Detiene el gateway de Hermes.
  Future<bool> stopHermes();

  /// Inicia el servicio de foreground (mantiene el motor vivo en segundo plano).
  Future<void> foregroundStart();

  /// Detiene el servicio de foreground.
  Future<void> foregroundStop();

  /// Última salida de logs de Hermes (para diagnóstico).
  Future<String> getRecentOutput();

  /// Desinstala el rootfs y libera espacio (~500 MB+).
  Future<bool> uninstallRootfs();

  /// Registra la conexión `127.0.0.1:9119` en [ConnectionManager] para que
  /// la UI pueda seleccionar el motor local como una instancia más.
  Future<void> ensureLocalConnection();

  /// Elimina la conexión del motor local de [ConnectionManager].
  Future<void> removeLocalConnection();
}

/// Implementación real sobre MethodChannel "hermes/local_engine".
class LocalEngine implements LocalEngineBridge {
  static const MethodChannel _channel = MethodChannel('hermes/local_engine');
  static const EventChannel _progressChannel =
      EventChannel('hermes/local_engine_progress');

  StreamSubscription<String>? _progressSubscription;
  final _progressController = StreamController<String>.broadcast();

  /// Referencia opcional al gestor de conexiones para registrar la
  /// instancia local cuando el gateway arranca.
  ConnectionManager? _connManager;

  LocalEngine({ConnectionManager? connManager}) : _connManager = connManager;

  /// Asigna el gestor de conexiones (puede llamarse después del constructor).
  void setConnectionManager(ConnectionManager? connManager) {
    _connManager = connManager;
  }

  @override
  Future<bool> get isArm64 =>
      _invokeBool('isArm64');

  @override
  Future<bool> get isProotReady =>
      _invokeBool('isProotReady');

  @override
  Future<bool> get isPythonInstalled =>
      _invokeBool('isPythonInstalled');

  @override
  Future<bool> get isHermesInstalled =>
      _invokeBool('isHermesInstalled');

  @override
  Future<bool> get isHermesRunning =>
      _invokeBool('isHermesRunning');

  @override
  Future<LocalEngineState> get state async {
    if (await isHermesRunning) return LocalEngineState.running;
    if (await isHermesInstalled) return LocalEngineState.hermesReady;
    if (await isPythonInstalled) return LocalEngineState.pythonReady;
    if (await isProotReady) return LocalEngineState.prootReady;
    return LocalEngineState.notInstalled;
  }

  @override
  Future<LocalEngineResult> installFull(
      {void Function(String)? onProgress}) async {
    // Escuchar eventos de progreso del lado nativo
    final sub = _progressChannel.receiveBroadcastStream().listen((event) {
      final msg = event.toString();
      onProgress?.call(msg);
      _progressController.add(msg);
    });
    try {
      final ok = await _channel.invokeMethod<bool>('installFull');
      return LocalEngineResult(ok: ok ?? false);
    } on PlatformException catch (e) {
      return LocalEngineResult(ok: false, message: e.message);
    } on MissingPluginException {
      return const LocalEngineResult(
        ok: false,
        message: 'Plugin no disponible (¿app en segundo plano?)',
      );
    } finally {
      await sub.cancel();
    }
  }

  @override
  Future<bool> startHermes() async {
    try {
      final ok = await _channel.invokeMethod<bool>('startHermes');
      if (ok == true) {
        // Registrar la conexión local en el gestor de conexiones
        await _connManager?.ensureLocalConnection();
      }
      return ok ?? false;
    } on PlatformException {
      return false;
    } on MissingPluginException {
      return false;
    }
  }

  @override
  Future<bool> stopHermes() async {
    try {
      final ok = await _channel.invokeMethod<bool>('stopHermes');
      // No eliminamos la conexión local: el usuario puede querer volver a
      // usarla sin reinstalar. Solo se elimina con uninstallRootfs() o
      // removeLocalConnection() explícito.
      return ok ?? false;
    } on PlatformException {
      return false;
    } on MissingPluginException {
      return false;
    }
  }

  @override
  Future<void> foregroundStart() async {
    try {
      await _channel.invokeMethod('foregroundStart');
    } on PlatformException {}
    on MissingPluginException {}
  }

  @override
  Future<void> foregroundStop() async {
    try {
      await _channel.invokeMethod('foregroundStop');
    } on PlatformException {}
    on MissingPluginException {}
  }

  @override
  Future<String> getRecentOutput() async {
    try {
      final out = await _channel.invokeMethod<String>('recentOutput');
      return out ?? '';
    } on PlatformException {
      return '';
    } on MissingPluginException {
      return '';
    }
  }

  @override
  Future<bool> uninstallRootfs() async {
    try {
      final ok = await _channel.invokeMethod<bool>('uninstallRootfs');
      if (ok == true) {
        await _connManager?.removeLocalConnection();
      }
      return ok ?? false;
    } on PlatformException {
      return false;
    } on MissingPluginException {
      return false;
    }
  }

  @override
  Future<void> ensureLocalConnection() async {
    await _connManager?.ensureLocalConnection();
  }

  @override
  Future<void> removeLocalConnection() async {
    await _connManager?.removeLocalConnection();
  }

  /// Stream de mensajes de progreso durante la instalación.
  Stream<String> get progressStream => _progressController.stream;

  Future<bool> _invokeBool(String method) async {
    try {
      final ok = await _channel.invokeMethod<bool>(method);
      return ok ?? false;
    } on PlatformException {
      return false;
    } on MissingPluginException {
      return false;
    }
  }

  void dispose() {
    _progressSubscription?.cancel();
    _progressController.close();
  }
}

/// Implementación ficticia para tests y plataformas no Android.
class LocalEngineMock implements LocalEngineBridge {
  @override
  Future<bool> get isArm64 async => false;

  @override
  Future<bool> get isProotReady async => false;

  @override
  Future<bool> get isPythonInstalled async => false;

  @override
  Future<bool> get isHermesInstalled async => false;

  @override
  Future<bool> get isHermesRunning async => false;

  @override
  Future<LocalEngineState> get state async =>
      LocalEngineState.notInstalled;

  @override
  Future<LocalEngineResult> installFull(
      {void Function(String)? onProgress}) async =>
      const LocalEngineResult(
        ok: false,
        message: 'Motor local solo disponible en Android',
      );

  @override
  Future<bool> startHermes() async => false;

  @override
  Future<bool> stopHermes() async => false;

  @override
  Future<void> foregroundStart() async {}

  @override
  Future<void> foregroundStop() async {}

  @override
  Future<String> getRecentOutput() async => '';

  @override
  Future<bool> uninstallRootfs() async => false;

  @override
  Future<void> ensureLocalConnection() async {}

  @override
  Future<void> removeLocalConnection() async {}

  @override
  Stream<String> get progressStream => const Stream.empty();

  void dispose() {}
}
