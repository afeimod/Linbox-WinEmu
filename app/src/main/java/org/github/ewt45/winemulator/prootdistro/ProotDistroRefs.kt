package org.github.ewt45.winemulator.prootdistro

/**
 * 参考 `proot_distro/helpers/docker/refs.py`。
 *
 * 把 Docker / OCI image reference (如 `ubuntu:24.04`,
 * `ghcr.io/foo/bar:1.0`) 解析成 `(registry, repo, tag)`,用于
 * 决定要走哪个 registry、仓库名是什么。
 */
object ProotDistroRefs {

    data class ParsedRef(
        val registry: String,
        val repo: String,
        val tag: String,
    )

    /**
     * 解析 image reference 为 (registry, repo, tag)。
     *
     * Docker Hub 镜像 (没有 registry host):
     *   'ubuntu'             -> ('', 'library/ubuntu', 'latest')
     *   'ubuntu:24.04'       -> ('', 'library/ubuntu', '24.04')
     *   'myuser/img:1.0'     -> ('', 'myuser/img', '1.0')
     *
     * 自定义 registry (host 含 '.' 或 ':'):
     *   'ghcr.io/foo/bar:latest' -> ('ghcr.io', 'foo/bar', 'latest')
     */
    fun parseImageRef(imageRef: String): ParsedRef {
        // 第一步:切分 registry 和 remainder
        val (registry, remainder) = splitRegistry(imageRef)

        // 第二步:切分 name 和 tag (从右侧找 ':',这样不会误伤 host 里的端口号)
        val name: String
        val tag: String
        if (":" in remainder) {
            val idx = remainder.lastIndexOf(':')
            name = remainder.substring(0, idx)
            tag = remainder.substring(idx + 1)
        } else {
            name = remainder
            tag = "latest"
        }

        val repo = if (registry.isEmpty()) {
            if ("/" in name) name else "library/$name"
        } else {
            name
        }
        return ParsedRef(registry, repo, tag)
    }

    private fun splitRegistry(imageRef: String): Pair<String, String> {
        val firstSlash = imageRef.indexOf('/')
        if (firstSlash == -1) return "" to imageRef
        val host = imageRef.substring(0, firstSlash)
        val remainder = imageRef.substring(firstSlash + 1)
        // host 含 '.' 或 ':' 才算 registry,否则是 Docker Hub 的 user/image 形式
        if ('.' in host || ':' in host) {
            // docker.io / index.docker.io 是 Docker Hub 的别名,清空走默认路径
            return if (host == "docker.io" || host == "index.docker.io") {
                "" to remainder
            } else {
                host to remainder
            }
        }
        return "" to imageRef
    }

    /**
     * 从 image reference 派生一个简短的本机别名。
     *
     *   'ubuntu:24.04'           -> 'ubuntu'
     *   'myuser/img:tag'         -> 'img'
     *   'ghcr.io/foo/bar:tag'    -> 'bar'
     *   'localhost:5000/foo:tag' -> 'foo'
     */
    fun deriveAlias(imageRef: String): String {
        val (_, repo, _) = parseImageRef(imageRef)
        return repo.substringAfterLast('/')
    }
}