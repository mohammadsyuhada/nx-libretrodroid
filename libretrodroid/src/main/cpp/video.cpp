/*
 *     Copyright (C) 2019  Filippo Scognamiglio
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

#include <GLES2/gl2.h>
#include <GLES3/gl3.h>
#include <EGL/egl.h>
#include <cstdlib>
#include <string>
#include <cmath>
#include <utility>
#include <sstream>

#include "log.h"

#include "video.h"
#include "renderers/es3/framebufferrenderer.h"
#include "renderers/es3/imagerendereres3.h"
#include "renderers/es2/imagerendereres2.h"

namespace libretrodroid {

static void printGLString(const char *name, GLenum s) {
    const char *v = (const char *) glGetString(s);
    LOGI("GL %s = %s\n", name, v);
}

GLuint loadShader(GLenum shaderType, const char* pSource) {
    GLuint shader = glCreateShader(shaderType);
    if (shader) {
        glShaderSource(shader, 1, &pSource, nullptr);
        glCompileShader(shader);
        GLint compiled = 0;
        glGetShaderiv(shader, GL_COMPILE_STATUS, &compiled);
        if (!compiled) {
            GLint infoLen = 0;
            glGetShaderiv(shader, GL_INFO_LOG_LENGTH, &infoLen);
            if (infoLen) {
                char* buf = (char*) malloc(infoLen);
                if (buf) {
                    glGetShaderInfoLog(shader, infoLen, nullptr, buf);
                    LOGE("Could not compile shader %d:\n%s\n",
                         shaderType, buf);
                    free(buf);
                }
                glDeleteShader(shader);
                shader = 0;
            }
        }
    }
    return shader;
}

GLuint createProgram(const char* pVertexSource, const char* pFragmentSource) {
    GLuint vertexShader = loadShader(GL_VERTEX_SHADER, pVertexSource);
    if (!vertexShader) {
        return 0;
    }

    GLuint pixelShader = loadShader(GL_FRAGMENT_SHADER, pFragmentSource);
    if (!pixelShader) {
        return 0;
    }

    GLuint program = glCreateProgram();
    if (program) {
        glAttachShader(program, vertexShader);
        glAttachShader(program, pixelShader);
        glLinkProgram(program);
        GLint linkStatus = GL_FALSE;
        glGetProgramiv(program, GL_LINK_STATUS, &linkStatus);
        if (linkStatus != GL_TRUE) {
            GLint bufLength = 0;
            glGetProgramiv(program, GL_INFO_LOG_LENGTH, &bufLength);
            if (bufLength) {
                char* buf = (char*) malloc(bufLength);
                if (buf) {
                    glGetProgramInfoLog(program, bufLength, nullptr, buf);
                    LOGE("Could not link program:\n%s\n", buf);
                    free(buf);
                }
            }
            glDeleteProgram(program);
            program = 0;
        }
    }
    return program;
}

void Video::updateProgram() {
    if (loadedShaderType.has_value() && loadedShaderType.value() == requestedShaderConfig) {
        return;
    }

    loadedShaderType = requestedShaderConfig;

    auto shaders = ShaderManager::getShader(requestedShaderConfig);
    linearTexture = shaders.linearTexture;

    shadersChain = {};

    std::for_each(shaders.passes.begin(), shaders.passes.end(), [&](const auto& item){
        auto shader = ShaderChainEntry { };

        shader.gProgram = createProgram(item.vertex.data(), item.fragment.data());
        if (!shader.gProgram) {
            LOGE("Could not create gl program.");
            throw std::runtime_error("Cannot create gl program");
        }

        shader.gvPositionHandle = glGetAttribLocation(shader.gProgram, "vPosition");

        shader.gvCoordinateHandle = glGetAttribLocation(shader.gProgram, "vCoordinate");

        shader.gTextureHandle = glGetUniformLocation(shader.gProgram, "texture");

        shader.gPreviousPassTextureHandle = glGetUniformLocation(shader.gProgram, "previousPass");

        shader.gTextureSizeHandle = glGetUniformLocation(shader.gProgram, "textureSize");

        shader.gScreenDensityHandle = glGetUniformLocation(shader.gProgram, "screenDensity");

        shadersChain.push_back(shader);
    });

    renderer->setShaders(shaders);
}

void Video::setPresetChain(std::optional<PresetChain> chain) {
    if (!presetSupported) {
        if (chain) LOGW("Preset chains need OpenGL ES 3");
        return;
    }
    requestedPreset = std::move(chain);
    presetDirty = true;
    isDirty = true;
}

void Video::setPresetParameter(const std::string& id, float value) {
    // Not presetDirty: a parameter change updates the live renderer instead of rebuilding it.
    if (requestedPreset) requestedPreset->params[id] = value;
    if (presetRenderer) presetRenderer->setParameter(id, value);
    isDirty = true;
}

std::optional<std::string> Video::takePresetError() {
    auto e = presetError;
    presetError.reset();
    return e;
}

void Video::updatePresetRenderer() {
    if (!presetDirty) return;
    presetDirty = false;
    presetRenderer.reset();
    if (!requestedPreset) return;
    try {
        presetRenderer = std::make_unique<PresetChainRenderer>(*requestedPreset);
    } catch (const std::exception& e) {
        LOGE("Preset chain failed: %s", e.what());
        presetError = e.what();
        requestedPreset.reset();
        presetDirty = true;
    }
}

void Video::renderFrame(bool force) {
    if (skipDuplicateFrames && !isDirty && !force) return;
    isDirty = false;

    glDisable(GL_DEPTH_TEST);

    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glClearColor(0.0F, 0.0F, 0.0F, 1.0F);
    glClear(GL_COLOR_BUFFER_BIT | GL_DEPTH_BUFFER_BIT);

    if (immersiveModeEnabled) {
        immersiveMode.renderBackground(
            videoLayout.getScreenWidth(),
            videoLayout.getScreenHeight(),
            videoLayout.getBackgroundVertices(),
            videoLayout.getRelativeForegroundBounds(),
            videoLayout.getFramebufferVertices().data(),
            renderer->getTexture()
        );
    }

    updateProgram();
    updatePresetRenderer();
    // Set per draw, not only when a renderer (re)creates its texture: a paused frame and a GL core's
    // framebuffer keep their texture, and a sharpness change must still show on them.
    // Bind on unit 0: a GL core may leave another unit active with its own texture bound, and it caches
    // those bindings. Unit 0 is rebound and cleared by the pass loop below anyway.
    GLint filter = linearTexture ? GL_LINEAR : GL_NEAREST;
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, renderer->getTexture());
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, filter);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, filter);
    glBindTexture(GL_TEXTURE_2D, 0);

    if (presetRenderer) {
        presetRenderer->render(
            renderer->getTexture(), (unsigned) getTextureWidth(), (unsigned) getTextureHeight(),
            videoLayout.getTextureCoordinates(), videoLayout.getForegroundVertices(), videoLayout.getFramebufferVertices(),
            videoLayout.getScreenWidth(), videoLayout.getScreenHeight(), linearTexture, frameCount);
        frameCount++;
        return;
    }

    for (int i = 0; i < shadersChain.size(); ++i) {
        auto shader = shadersChain[i];
        auto passData = renderer->getPassData(i);
        auto isLastPass = i == shadersChain.size() - 1;

        glBindFramebuffer(GL_FRAMEBUFFER, passData.framebuffer.value_or(0));

        glViewport(
            0,
            0,
            passData.width.value_or(videoLayout.getScreenWidth()),
            passData.height.value_or(videoLayout.getScreenHeight())
        );

        glUseProgram(shader.gProgram);

        auto vertices = isLastPass ? videoLayout.getForegroundVertices() : videoLayout.getFramebufferVertices();
        glVertexAttribPointer(shader.gvPositionHandle, 2, GL_FLOAT, GL_FALSE, 0, vertices.data());
        glEnableVertexAttribArray(shader.gvPositionHandle);

        auto coordinates = videoLayout.getTextureCoordinates();
        glVertexAttribPointer(shader.gvCoordinateHandle, 2, GL_FLOAT, GL_FALSE, 0, coordinates.data());
        glEnableVertexAttribArray(shader.gvCoordinateHandle);

        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, renderer->getTexture());
        glUniform1i(shader.gTextureHandle, 0);

        if (shader.gPreviousPassTextureHandle != -1 && passData.texture.has_value()) {
            glActiveTexture(GL_TEXTURE0 + 1);
            glBindTexture(GL_TEXTURE_2D, passData.texture.value());
            glUniform1i(shader.gPreviousPassTextureHandle, 1);
        }

        glUniform2f(shader.gTextureSizeHandle, getTextureWidth(), getTextureHeight());

        glUniform1f(shader.gScreenDensityHandle, getScreenDensity());

        glDrawArrays(GL_TRIANGLES, 0, 6);

        glDisableVertexAttribArray(shader.gvPositionHandle);
        glDisableVertexAttribArray(shader.gvCoordinateHandle);

        if (shader.gPreviousPassTextureHandle != -1 && passData.texture.has_value()) {
            glActiveTexture(GL_TEXTURE0 + 1);
            glBindTexture(GL_TEXTURE_2D, 0);
        }
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, 0);

        glUseProgram(0);
    }
    frameCount++;
}

void Video::renderPausedFrame() {
    // A GL core may leave state set after its last frame that renderFrame doesn't reset (it normally draws
    // inside the core's video callback). Neutralise it for this draw and restore it, so the core resumes
    // with exactly the state it left (GL cores cache their state).
    GLboolean scissor = glIsEnabled(GL_SCISSOR_TEST);
    GLboolean blend = glIsEnabled(GL_BLEND);
    GLboolean stencil = glIsEnabled(GL_STENCIL_TEST);
    GLboolean cull = glIsEnabled(GL_CULL_FACE);
    GLboolean depth = glIsEnabled(GL_DEPTH_TEST);
    GLint arrayBuffer = 0;
    glGetIntegerv(GL_ARRAY_BUFFER_BINDING, &arrayBuffer);
    GLboolean colorMask[4];
    glGetBooleanv(GL_COLOR_WRITEMASK, colorMask);

    // Only a GL core (ES3 framebuffer renderer) can leave a vertex array object bound; renderFrame feeds
    // client-side attribute arrays, which a bound VAO would reject (and the draw would clobber the core's VAO).
    bool glCore = rendersInVideoCallback();
    GLint vertexArray = 0;
    if (glCore) glGetIntegerv(GL_VERTEX_ARRAY_BINDING, &vertexArray);

    glDisable(GL_SCISSOR_TEST);
    glDisable(GL_BLEND);
    glDisable(GL_STENCIL_TEST);
    glDisable(GL_CULL_FACE);
    glBindBuffer(GL_ARRAY_BUFFER, 0);
    glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
    if (glCore) glBindVertexArray(0);

    renderFrame(true);

    if (glCore) glBindVertexArray(vertexArray);
    glColorMask(colorMask[0], colorMask[1], colorMask[2], colorMask[3]);
    glBindBuffer(GL_ARRAY_BUFFER, arrayBuffer);
    if (scissor) glEnable(GL_SCISSOR_TEST);
    if (blend) glEnable(GL_BLEND);
    if (stencil) glEnable(GL_STENCIL_TEST);
    if (cull) glEnable(GL_CULL_FACE);
    if (depth) glEnable(GL_DEPTH_TEST);  // renderFrame disables it
}

float Video::getScreenDensity() {
    return std::min(videoLayout.getScreenWidth() / getTextureWidth(), videoLayout.getScreenHeight() / getTextureHeight());
}

float Video::getTextureWidth() {
    return renderer->lastFrameSize.first;
}

float Video::getTextureHeight() {
    return renderer->lastFrameSize.second;
}

void Video::onNewFrame(const void *data, unsigned width, unsigned height, size_t pitch) {
    if (data != nullptr) {
        renderer->onNewFrame(data, width, height, pitch);
        isDirty = true;
    }
}

void Video::updateScreenSize(unsigned width, unsigned height) {
    videoLayout.updateScreenSize(width, height);
}

void Video::updateViewportSize(Rect viewportRect) {
    videoLayout.updateViewportSize(viewportRect);
}

void Video::updateViewportAlignment(unsigned int viewportAlignment) {
    videoLayout.updateViewportAlignment(viewportAlignment);
}

void Video::updateRendererSize(unsigned int width, unsigned int height) {
    LOGD("Updating renderer size: %d x %d", width, height);
    renderer->updateRenderedResolution(width, height);
    videoLayout.updateContentSize(width, height);
}

void Video::updateScaleMode(unsigned int scaleMode) {
    videoLayout.updateScaleMode(scaleMode);
}

void Video::updateScreenOffset(float x, float y) {
    videoLayout.updateScreenOffset(x, y);
}

void Video::updateContentSize(unsigned width, unsigned height) {
    videoLayout.updateContentSize(width, height);
}

void Video::updateRotation(float rotation) {
    videoLayout.updateRotation(rotation);
}

Video::Video(
    RenderingOptions renderingOptions,
    ShaderManager::Config shaderConfig,
    bool bottomLeftOrigin,
    float rotation,
    bool skipDuplicateFrames,
    bool immersiveModeEnabled,
    Rect viewportRect,
    ImmersiveMode::Config immersiveModeConfig,
    unsigned int viewportAlignment
) :
    requestedShaderConfig(std::move(shaderConfig)),
    skipDuplicateFrames(skipDuplicateFrames),
    immersiveModeEnabled(immersiveModeEnabled),
    immersiveMode(immersiveModeConfig),
    videoLayout(bottomLeftOrigin, rotation, viewportRect, viewportAlignment) {

    printGLString("Version", GL_VERSION);
    printGLString("Vendor", GL_VENDOR);
    printGLString("Renderer", GL_RENDERER);
    printGLString("Extensions", GL_EXTENSIONS);
    initializeGLESLogCallbackIfNeeded();

    LOGI("Initializing graphics");

    glViewport(0, 0, videoLayout.getScreenWidth(), videoLayout.getScreenHeight());

    glUseProgram(0);

    initializeRenderer(renderingOptions);
}

void Video::updateShaderType(ShaderManager::Config shaderConfig) {
    requestedShaderConfig = std::move(shaderConfig);
}

void Video::initializeRenderer(RenderingOptions renderingOptions) {
    auto shaders = ShaderManager::getShader(requestedShaderConfig);

    if (renderingOptions.hardwareAccelerated) {
        renderer = new FramebufferRenderer(
            renderingOptions.width,
            renderingOptions.height,
            renderingOptions.useDepth,
            renderingOptions.useStencil,
            std::move(shaders)
        );
    } else {
        if (renderingOptions.openglESVersion >= 3) {
            renderer = new ImageRendererES3();
        } else {
            renderer = new ImageRendererES2();
        }
    }

    // Preset chains render through ES3 framebuffers.
    presetSupported = renderingOptions.openglESVersion >= 3;

    renderer->setPixelFormat(renderingOptions.pixelFormat);
    updateProgram();
}

void Video::updateAspectRatio(float aspectRatio) {
    videoLayout.updateAspectRatio(aspectRatio);
}

} //namespace libretrodroid
