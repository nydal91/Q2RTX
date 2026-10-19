/*
Copyright (C) 2026 Q2RTX Android port contributors

This program is free software; you can redistribute it and/or modify
it under the terms of the GNU General Public License as published by
the Free Software Foundation; either version 2 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU General Public License for more details.
*/

//
// vulkan_loader.c -- choose and load the Vulkan driver at runtime (Android)
//
// The launcher passes these on the command line (all set with +set):
//   vk_driver_dir   private directory holding a custom driver, e.g. .../files/gpu_drivers/turnip/
//   vk_driver_name  file name of the driver library in that directory, e.g. libvulkan_freedreno.so
//   vk_hook_dir     the app's nativeLibraryDir, where libadrenotools' hook libraries are
// With no custom driver, the system libvulkan is used as usual.
//

#include "shared/shared.h"
#include "common/common.h"
#include "common/cvar.h"
#include "vulkan_loader.h"

#include <SDL2/SDL_syswm.h>
#include <adrenotools/driver.h>
#include <dlfcn.h>

static void *vulkan_library;
static char driver_description[256] = "system Vulkan driver";

const char *VK_DriverDescription(void)
{
    return driver_description;
}

static void *open_custom_driver(void)
{
    cvar_t *dir  = Cvar_Get("vk_driver_dir", "", CVAR_NOSET);
    cvar_t *name = Cvar_Get("vk_driver_name", "", CVAR_NOSET);
    cvar_t *hook = Cvar_Get("vk_hook_dir", "", CVAR_NOSET);

    if (!dir->string[0] || !name->string[0])
        return NULL;

    if (!hook->string[0]) {
        Com_WPrintf("Custom Vulkan driver requested, but vk_hook_dir is not set.\n");
        return NULL;
    }

    // adrenotools concatenates directory and name, so the directory needs a trailing slash
    char driver_dir[MAX_OSPATH];
    Q_strlcpy(driver_dir, dir->string, sizeof(driver_dir));
    size_t len = strlen(driver_dir);
    if (len && driver_dir[len - 1] != '/')
        Q_strlcat(driver_dir, "/", sizeof(driver_dir));

    Com_Printf("Loading custom Vulkan driver %s%s\n", driver_dir, name->string);

    void *lib = adrenotools_open_libvulkan(RTLD_NOW | RTLD_LOCAL, ADRENOTOOLS_DRIVER_CUSTOM,
                                           NULL, hook->string, driver_dir, name->string, NULL, NULL);
    if (!lib) {
        Com_WPrintf("libadrenotools couldn't load the custom Vulkan driver %s%s; "
                    "falling back to the system driver.\n", driver_dir, name->string);
        Q_snprintf(driver_description, sizeof(driver_description),
                   "system Vulkan driver (custom driver %s failed to load)", name->string);
        return NULL;
    }

    Q_snprintf(driver_description, sizeof(driver_description), "custom Vulkan driver %s", name->string);
    return lib;
}

bool VK_LoadLibrary(void)
{
    if (vulkan_library)
        return true;

    void *lib = open_custom_driver();
    if (!lib)
        lib = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
    if (!lib) {
        Com_EPrintf("Couldn't load libvulkan.so: %s\n", dlerror());
        return false;
    }

    PFN_vkGetInstanceProcAddr get_instance_proc_addr =
        (PFN_vkGetInstanceProcAddr)dlsym(lib, "vkGetInstanceProcAddr");
    if (!get_instance_proc_addr) {
        Com_EPrintf("libvulkan.so has no vkGetInstanceProcAddr\n");
        dlclose(lib);
        return false;
    }

    volkInitializeCustom(get_instance_proc_addr);
    vulkan_library = lib;
    Com_Printf("Vulkan: using the %s (instance version %u.%u)\n", driver_description,
               VK_API_VERSION_MAJOR(volkGetInstanceVersion()), VK_API_VERSION_MINOR(volkGetInstanceVersion()));
    return true;
}

VkResult VK_CreateWindowSurface(SDL_Window *window, VkInstance instance, VkSurfaceKHR *surface)
{
    SDL_SysWMinfo info;
    SDL_VERSION(&info.version);
    if (!SDL_GetWindowWMInfo(window, &info) || info.subsystem != SDL_SYSWM_ANDROID || !info.info.android.window) {
        Com_DPrintf("No Android window to create a Vulkan surface for: %s\n", SDL_GetError());
        return VK_ERROR_SURFACE_LOST_KHR;
    }

    if (!vkCreateAndroidSurfaceKHR) {
        Com_EPrintf("vkCreateAndroidSurfaceKHR is not available\n");
        return VK_ERROR_EXTENSION_NOT_PRESENT;
    }

    VkAndroidSurfaceCreateInfoKHR create_info = {
        .sType = VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR,
        .window = info.info.android.window,
    };
    return vkCreateAndroidSurfaceKHR(instance, &create_info, NULL, surface);
}
