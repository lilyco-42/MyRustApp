use jni::objects::JClass;
use jni::sys::jint;
use jni::EnvUnowned;

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_example_myrustapp_NativeLib_nativeIncrement(
    _env: EnvUnowned,
    _class: JClass,
    count: jint,
) -> jint {
    count + 1
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_increment() {
        let count: jint = 0;
        assert_eq!(count + 1, 1);
        let count: jint = 41;
        assert_eq!(count + 1, 42);
    }

    #[test]
    fn test_negative() {
        let count: jint = -5;
        assert_eq!(count + 1, -4);
    }
}
