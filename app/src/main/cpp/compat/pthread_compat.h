#pragma once
#include <pthread.h>

#if defined(__ANDROID__)
inline int pthread_attr_getstackaddr(const pthread_attr_t* attr, void** stackaddr) {
    size_t stacksize = 0;
    return pthread_attr_getstack(attr, stackaddr, &stacksize);
}
#endif
