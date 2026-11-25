//! Funciones de bionic que faltan en Android viejos y que piden Rust std, mio o if-addrs.
//!
//! - `epoll_create1`, `fstat64` (ARM y x86 de 32 bits) y `dl_iterate_phdr` (ARM): faltan en
//!   Android 4.4. Los ABIs de 64 bits no existen antes de Android 5.
//! - `getifaddrs`/`freeifaddrs`: llegaron recién en Android 7.0 (API 24); las usa el login por
//!   Spotify Connect (mDNS) en todos los ABIs.
//!
//! Cuando la libc del sistema ya las exporta, el linker dinámico usa esas (libc va antes en el
//! orden de búsqueda), así que estas implementaciones solo actúan donde faltan.

#![cfg(target_os = "android")]

use libc::{c_int, c_void};

#[cfg(any(target_arch = "arm", target_arch = "x86"))]
/// epoll_create1 llegó a bionic en API 21; la syscall existe desde Linux 2.6.27.
#[no_mangle]
pub unsafe extern "C" fn epoll_create1(flags: c_int) -> c_int {
    libc::syscall(libc::SYS_epoll_create1, flags) as c_int
}

#[cfg(any(target_arch = "arm", target_arch = "x86"))]
/// En bionic de 32 bits `struct stat` y `struct stat64` tienen el mismo layout.
#[no_mangle]
pub unsafe extern "C" fn fstat64(fd: c_int, buf: *mut c_void) -> c_int {
    libc::fstat(fd, buf as *mut libc::stat)
}

#[cfg(target_arch = "arm")]
/// Solo se usa para recorrer módulos al generar backtraces (compilamos con panic=abort).
#[no_mangle]
pub unsafe extern "C" fn dl_iterate_phdr(
    _callback: Option<unsafe extern "C" fn(*mut c_void, usize, *mut c_void) -> c_int>,
    _data: *mut c_void,
) -> c_int {
    0
}

// ---------------------------------------------------------------- getifaddrs (solo IPv4)

const SIOCGIFCONF: libc::c_int = 0x8912;
const SIOCGIFFLAGS: libc::c_int = 0x8913;
const SIOCGIFBRDADDR: libc::c_int = 0x8919;
const SIOCGIFNETMASK: libc::c_int = 0x891b;
const IFNAMSIZ: usize = 16;

/// `struct ifreq`: nombre + unión (16 bytes en 32 bits, 24 en 64 bits por `struct ifmap`).
#[repr(C)]
#[derive(Clone, Copy)]
struct IfReq {
    name: [libc::c_char; IFNAMSIZ],
    #[cfg(target_pointer_width = "64")]
    data: [u64; 3],
    #[cfg(target_pointer_width = "32")]
    data: [u32; 4],
}

#[repr(C)]
struct IfConf {
    len: c_int,
    buf: *mut c_void,
}

/// Un nodo de la lista: `ifaddrs` va primero, así el puntero al nodo es el de `ifaddrs`.
#[repr(C)]
struct Node {
    ifa: libc::ifaddrs,
    name: [libc::c_char; IFNAMSIZ + 1],
    addr: libc::sockaddr_in,
    mask: libc::sockaddr_in,
    brd: libc::sockaddr_in,
}

unsafe fn sockaddr_of(req: &IfReq) -> libc::sockaddr_in {
    std::ptr::read_unaligned(req.data.as_ptr() as *const libc::sockaddr_in)
}

#[no_mangle]
pub unsafe extern "C" fn getifaddrs(out: *mut *mut libc::ifaddrs) -> c_int {
    if out.is_null() {
        return -1;
    }
    *out = std::ptr::null_mut();
    let fd = libc::socket(libc::AF_INET, libc::SOCK_DGRAM | libc::SOCK_CLOEXEC, 0);
    if fd < 0 {
        return -1;
    }
    let mut reqs: Vec<IfReq> = vec![std::mem::zeroed(); 64];
    let mut conf = IfConf {
        len: (reqs.len() * std::mem::size_of::<IfReq>()) as c_int,
        buf: reqs.as_mut_ptr() as *mut c_void,
    };
    if libc::ioctl(fd, SIOCGIFCONF as _, &mut conf as *mut IfConf) < 0 {
        libc::close(fd);
        return -1;
    }
    let count = conf.len as usize / std::mem::size_of::<IfReq>();
    let mut head: *mut libc::ifaddrs = std::ptr::null_mut();
    for req in reqs.iter().take(count).rev() {
        let addr = sockaddr_of(req);
        if addr.sin_family as c_int != libc::AF_INET {
            continue;
        }
        let mut q = *req;
        let flags = if libc::ioctl(fd, SIOCGIFFLAGS as _, &mut q as *mut IfReq) == 0 {
            std::ptr::read_unaligned(q.data.as_ptr() as *const libc::c_short) as u16 as libc::c_uint
        } else {
            0
        };
        let mut q = *req;
        let mask = if libc::ioctl(fd, SIOCGIFNETMASK as _, &mut q as *mut IfReq) == 0 {
            sockaddr_of(&q)
        } else {
            std::mem::zeroed()
        };
        let mut q = *req;
        let has_brd = flags & (libc::IFF_BROADCAST as libc::c_uint) != 0
            && libc::ioctl(fd, SIOCGIFBRDADDR as _, &mut q as *mut IfReq) == 0;
        let brd = if has_brd { sockaddr_of(&q) } else { std::mem::zeroed() };

        let node: *mut Node = Box::into_raw(Box::new(std::mem::zeroed::<Node>()));
        let n = &mut *node;
        n.name[..IFNAMSIZ].copy_from_slice(&req.name);
        n.addr = addr;
        n.mask = mask;
        n.brd = brd;
        n.ifa.ifa_next = head;
        n.ifa.ifa_name = n.name.as_mut_ptr();
        n.ifa.ifa_flags = flags;
        n.ifa.ifa_addr = &mut n.addr as *mut _ as *mut libc::sockaddr;
        n.ifa.ifa_netmask = &mut n.mask as *mut _ as *mut libc::sockaddr;
        n.ifa.ifa_ifu = if has_brd { &mut n.brd as *mut _ as *mut libc::sockaddr } else { std::ptr::null_mut() };
        n.ifa.ifa_data = std::ptr::null_mut();
        head = node as *mut libc::ifaddrs;
    }
    libc::close(fd);
    *out = head;
    0
}

#[no_mangle]
pub unsafe extern "C" fn freeifaddrs(mut ifa: *mut libc::ifaddrs) {
    while !ifa.is_null() {
        let next = (*ifa).ifa_next;
        drop(Box::from_raw(ifa as *mut Node));
        ifa = next;
    }
}

// ---------------------------------------------------------------- dlsym(RTLD_DEFAULT) seguro

#[cfg(target_pointer_width = "32")]
extern "C" {
    fn __real_dlsym(handle: *mut c_void, symbol: *const libc::c_char) -> *mut c_void;
}

/// Reemplazo de `dlsym` para nuestro binario (enlazado con `-Wl,--wrap=dlsym`, ver build.rs).
///
/// Lo usan getrandom, rustix y panic_abort para detectar funciones opcionales de libc. Con
/// `RTLD_DEFAULT`, el linker de Android 4.4 hace una búsqueda lineal que crashea (SIGFPE); acá la
/// búsqueda se hace directo en libc/libm/libdl, que es donde viven todas esas funciones.
#[cfg(target_pointer_width = "32")]
#[no_mangle]
pub unsafe extern "C" fn __wrap_dlsym(handle: *mut c_void, symbol: *const libc::c_char) -> *mut c_void {
    if handle != libc::RTLD_DEFAULT {
        return __real_dlsym(handle, symbol);
    }
    for lib in [c"libc.so", c"libm.so", c"libdl.so"] {
        let h = libc::dlopen(lib.as_ptr(), libc::RTLD_NOW);
        if !h.is_null() {
            let p = __real_dlsym(h, symbol);
            if !p.is_null() {
                return p;
            }
        }
    }
    std::ptr::null_mut()
}
