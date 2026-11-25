//! HTTPS desde Rust (hyper + rustls con raíces webpki embebidas).
//!
//! Android 4.4 trae TLS 1.2 deshabilitado por defecto en HttpsURLConnection y le faltan
//! certificados raíz modernos. Haciendo las peticiones acá, el login, la Web API y las
//! carátulas funcionan igual en cualquier versión de Android.
//! El resultado vuelve a Java por
//! `NativeEngine.onHttp(int reqId, int status, byte[] body, String retryAfter)`.

use std::sync::OnceLock;

use bytes::Bytes;
use http::Request;
use http_body_util::BodyExt;
use jni::objects::{JClass, JValue};
use librespot_core::http_client::HttpClient;
use serde_json::Value;

static CLIENT: OnceLock<HttpClient> = OnceLock::new();

fn client() -> &'static HttpClient {
    CLIENT.get_or_init(|| HttpClient::new(None))
}

async fn perform(method: &str, url: &str, headers: &str, body: String) -> Result<(u16, Bytes, String), String> {
    let mut b = Request::builder().method(method).uri(url);
    if let Ok(Value::Object(map)) = serde_json::from_str::<Value>(headers) {
        for (k, v) in map {
            if let Some(v) = v.as_str() {
                b = b.header(k.as_str(), v);
            }
        }
    }
    let req = b.body(Bytes::from(body)).map_err(|e| e.to_string())?;
    let resp = client().request_fut(req).map_err(|e| e.to_string())?.await.map_err(|e| e.to_string())?;
    let status = resp.status().as_u16();
    // Ante un 429 Spotify dice cuántos segundos esperar; Java respeta esa pausa.
    let retry_after = resp
        .headers()
        .get(http::header::RETRY_AFTER)
        .and_then(|v| v.to_str().ok())
        .unwrap_or("")
        .to_string();
    let data = resp.into_body().collect().await.map_err(|e| e.to_string())?.to_bytes();
    Ok((status, data, retry_after))
}

pub(crate) async fn request(method: String, url: String, headers: String, body: String, req_id: i32) {
    let (status, data, retry_after) = match perform(&method, &url, &headers, body).await {
        Ok(r) => r,
        Err(e) => (0u16, Bytes::from(e.into_bytes()), String::new()), // status 0 = error de red
    };
    let Ok(mut env) = crate::vm().attach_current_thread_permanently() else { return };
    let Ok(arr) = env.byte_array_from_slice(&data) else { return };
    let Ok(retry) = env.new_string(retry_after) else { return };
    let class: &JClass = crate::bridge_class().as_obj().into();
    if env
        .call_static_method(
            class,
            "onHttp",
            "(II[BLjava/lang/String;)V",
            &[
                JValue::Int(req_id),
                JValue::Int(status as i32),
                JValue::Object(&arr),
                JValue::Object(&retry),
            ],
        )
        .is_err()
    {
        let _ = env.exception_clear();
    }
    let _ = env.delete_local_ref(arr);
    let _ = env.delete_local_ref(retry);
}
