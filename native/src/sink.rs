//! Salida de audio: entrega PCM s16 a Java (`NativeEngine.onPcm`), que lo escribe en un
//! `AudioTrack`. La escritura bloqueante del lado Java da la contrapresión natural.
//!
//! Para no generar basura en el GC (clave en teléfonos viejos) se reutiliza un único
//! `short[]` global que solo crece si llega un paquete más grande.

use std::sync::OnceLock;

use jni::objects::{GlobalRef, JClass, JShortArray, JStaticMethodID, JValue};
use jni::signature::{Primitive, ReturnType};
use jni::JNIEnv;
use librespot_playback::audio_backend::{Sink, SinkError, SinkResult};
use librespot_playback::convert::Converter;
use librespot_playback::decoder::AudioPacket;

struct Ids {
    on_pcm: JStaticMethodID,
    on_start: JStaticMethodID,
    on_stop: JStaticMethodID,
}

static IDS: OnceLock<Ids> = OnceLock::new();

pub(crate) fn cache_method_ids(env: &JNIEnv) -> jni::errors::Result<()> {
    if IDS.get().is_some() {
        return Ok(());
    }
    // `get_static_method_id` necesita &mut; usamos un env clonado local.
    let mut env = unsafe { env.unsafe_clone() };
    let class: &JClass = crate::bridge_class().as_obj().into();
    let ids = Ids {
        on_pcm: env.get_static_method_id(class, "onPcm", "([SI)V")?,
        on_start: env.get_static_method_id(class, "onAudioStart", "()V")?,
        on_stop: env.get_static_method_id(class, "onAudioStop", "()V")?,
    };
    let _ = IDS.set(ids);
    Ok(())
}

pub struct JniSink {
    buffer: Option<GlobalRef>,
    capacity: usize,
}

impl JniSink {
    pub fn new() -> Self {
        JniSink { buffer: None, capacity: 0 }
    }

    fn call_void(id: fn(&Ids) -> JStaticMethodID) -> SinkResult<()> {
        let ids = IDS.get().ok_or_else(|| SinkError::NotConnected("JNI sin inicializar".into()))?;
        let mut env = crate::vm()
            .attach_current_thread_permanently()
            .map_err(|e| SinkError::NotConnected(e.to_string()))?;
        let class: &JClass = crate::bridge_class().as_obj().into();
        let r = unsafe {
            env.call_static_method_unchecked(class, id(ids), ReturnType::Primitive(Primitive::Void), &[])
        };
        if r.is_err() {
            let _ = env.exception_clear();
            return Err(SinkError::StateChange("callback de audio falló".into()));
        }
        Ok(())
    }

    fn push(&mut self, samples: &[i16]) -> SinkResult<()> {
        let ids = IDS.get().ok_or_else(|| SinkError::NotConnected("JNI sin inicializar".into()))?;
        let mut env = crate::vm()
            .attach_current_thread_permanently()
            .map_err(|e| SinkError::NotConnected(e.to_string()))?;
        let err = |e: jni::errors::Error| SinkError::OnWrite(e.to_string());

        if self.buffer.is_none() || self.capacity < samples.len() {
            let cap = samples.len().max(8192);
            let arr = env.new_short_array(cap as i32).map_err(err)?;
            self.buffer = Some(env.new_global_ref(&arr).map_err(err)?);
            let _ = env.delete_local_ref(arr);
            self.capacity = cap;
        }
        let global = self.buffer.as_ref().unwrap();
        // Vista no-propietaria sobre la referencia global (no se libera al salir).
        let arr = unsafe { JShortArray::from_raw(global.as_obj().as_raw()) };
        env.set_short_array_region(&arr, 0, samples).map_err(err)?;

        let class: &JClass = crate::bridge_class().as_obj().into();
        let r = unsafe {
            env.call_static_method_unchecked(
                class,
                ids.on_pcm,
                ReturnType::Primitive(Primitive::Void),
                &[JValue::Object(&arr).as_jni(), JValue::Int(samples.len() as i32).as_jni()],
            )
        };
        std::mem::forget(arr);
        if r.is_err() {
            let _ = env.exception_clear();
            return Err(SinkError::OnWrite("onPcm lanzó una excepción".into()));
        }
        Ok(())
    }
}

impl Sink for JniSink {
    fn start(&mut self) -> SinkResult<()> {
        Self::call_void(|i| i.on_start)
    }

    fn stop(&mut self) -> SinkResult<()> {
        Self::call_void(|i| i.on_stop)
    }

    fn write(&mut self, packet: AudioPacket, converter: &mut Converter) -> SinkResult<()> {
        match packet {
            AudioPacket::Samples(samples) => {
                let s16 = converter.f64_to_s16(&samples);
                self.push(&s16)
            }
            AudioPacket::Raw(_) => Err(SinkError::InvalidParams("passthrough no soportado".into())),
        }
    }
}
