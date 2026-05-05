import GLKit
import simd

/// Port 1:1 di PerimeterRenderer.kt — palette sci-fi Hubique.
/// Usa GLES 2.0 via GLKit. Chiamato da ScanningViewController.glkView(_:drawIn:).
class PerimeterRenderer {

    // MARK: – Costanti

    private static let FLOOR_OFFSET: Float = 0.005

    private static let COLOR_CONFIRMED:      [Float] = [0.12, 0.85, 1.00, 1.00]
    private static let COLOR_LIVE:           [Float] = [0.92, 0.08, 0.58, 0.95]
    private static let COLOR_GHOST_CORNER:   [Float] = [0.92, 0.08, 0.60, 1.00]
    private static let COLOR_DOT:            [Float] = [0.12, 0.90, 1.00, 1.00]
    private static let COLOR_WALL_FILL:      [Float] = [0.42, 0.16, 0.80, 0.18]
    private static let COLOR_LIVE_WALL_FILL: [Float] = [0.90, 0.08, 0.58, 0.22]
    private static let COLOR_HEIGHT_LINE:    [Float] = [0.08, 0.90, 1.00, 1.00]
    private static let COLOR_WALL_GRID:      [Float] = [0.65, 0.30, 1.00, 0.28]
    private static let COLOR_WALL_PREVIEW:   [Float] = [0.48, 0.18, 0.88, 0.55]
    private static let COLOR_AXIS_HINT:      [Float] = [0.50, 0.18, 0.85, 0.18]
    private static let COLOR_TOP_CURSOR:     [Float] = [1.00, 0.65, 0.10, 1.00]

    private static let VERT_SRC = """
        uniform mat4 uMVP;
        attribute vec4 aPos;
        void main() {
            gl_Position  = uMVP * aPos;
            gl_PointSize = 20.0;
        }
        """
    private static let FRAG_SRC = """
        precision mediump float;
        uniform vec4 uColor;
        void main() { gl_FragColor = uColor; }
        """

    // MARK: – GL state

    private var program:  GLuint = 0
    private var aPosLoc:  GLint  = 0
    private var uMVPLoc:  GLint  = 0
    private var uColorLoc: GLint = 0

    // MARK: – Setup

    func setup() {
        let vert = compileShader(GLenum(GL_VERTEX_SHADER),   Self.VERT_SRC)
        let frag = compileShader(GLenum(GL_FRAGMENT_SHADER), Self.FRAG_SRC)
        program = glCreateProgram()
        glAttachShader(program, vert); glAttachShader(program, frag)
        glLinkProgram(program)
        glDeleteShader(vert); glDeleteShader(frag)
        aPosLoc   = glGetAttribLocation(program,  "aPos")
        uMVPLoc   = glGetUniformLocation(program, "uMVP")
        uColorLoc = glGetUniformLocation(program, "uColor")
    }

    // MARK: – Draw

    func draw(
        confirmedPts:        [SIMD3<Float>],
        livePoint:           SIMD3<Float>?,
        isClosed:            Bool,
        canClose:            Bool,
        wallHeight:          Float,
        capturePhase:        PerimeterCapture.CapturePhase,
        liveHeightM:         Float?,
        viewMatrix:          simd_float4x4,
        projMatrix:          simd_float4x4,
        reticleSnapped:      Bool          = false,
        goniometerCenter:    SIMD3<Float>? = nil,
        goniometerAngle:     Float         = 0,
        goniometerSnapAngle: Float?        = nil,
        currentFloorY:       Float?        = nil,
        topCursorPoint:      SIMD3<Float>? = nil
    ) {
        guard program != 0 else { return }

        let mvp = matToArray(projMatrix * viewMatrix)
        glUseProgram(program)
        mvp.withUnsafeBufferPointer { glUniformMatrix4fv(uMVPLoc, 1, GLboolean(GL_FALSE), $0.baseAddress) }

        let floorY = confirmedPts.first?.y ?? livePoint?.y ?? 0
        let drawY  = floorY + Self.FLOOR_OFFSET

        // ── 0. Wall fills
        if !isClosed && confirmedPts.count >= 2 {
            drawConfirmedWallFills(confirmedPts, drawY: drawY, wallH: wallHeight)
            drawWallGridLines(confirmedPts, drawY: drawY, wallH: wallHeight)
        }

        // ── 0b. Live wall fill fucsia
        if !isClosed && !confirmedPts.isEmpty, let live = livePoint {
            drawLiveWallFill(confirmedPts.last!, live, drawY: drawY, wallH: wallHeight)
        }

        // ── 0c. Height preview
        if capturePhase == .awaitHeight && confirmedPts.count == 1,
           let h = liveHeightM, h > 0.1 {
            drawHeightPreview(confirmedPts[0], drawY: drawY, heightM: h)
        }

        // ── 1. Axis hints
        if !isClosed && confirmedPts.count >= 2 {
            drawAxisHints(confirmedPts.last!, confirmedPts[confirmedPts.count - 2], drawY: drawY)
        }

        // ── 2. Confirmed segments cyan
        if confirmedPts.count >= 2 {
            var v = [Float](); v.reserveCapacity(confirmedPts.count * 3)
            for pt in confirmedPts { v += [pt.x, drawY, pt.z] }
            setColor(Self.COLOR_CONFIRMED); glLineWidth(5)
            prim(GLenum(GL_LINE_STRIP), v, GLsizei(confirmedPts.count))
        }

        // ── 3. Close hint
        if !isClosed && canClose && confirmedPts.count >= 2 {
            let l = confirmedPts.last!; let f = confirmedPts.first!; let topY = drawY + wallHeight
            glEnable(GLenum(GL_BLEND)); glBlendFunc(GLenum(GL_SRC_ALPHA), GLenum(GL_ONE_MINUS_SRC_ALPHA))
            setColor([0.12, 0.85, 1.00, 0.60]); glLineWidth(3)
            prim(GLenum(GL_LINE_LOOP),
                 [l.x, drawY, l.z, f.x, drawY, f.z, f.x, topY, f.z, l.x, topY, l.z], 4)
            glLineWidth(2)
            prim(GLenum(GL_LINES),
                 [l.x, drawY, l.z, l.x, topY, l.z, f.x, drawY, f.z, f.x, topY, f.z], 4)
            glDisable(GLenum(GL_BLEND))
        }

        // ── 4. Confirmed closure line
        if isClosed && confirmedPts.count >= 3 {
            let l = confirmedPts.last!; let f = confirmedPts.first!
            setColor(Self.COLOR_CONFIRMED); glLineWidth(5)
            prim(GLenum(GL_LINES), [l.x, drawY, l.z, f.x, drawY, f.z], 2)
        }

        // ── 5. Live segment fucsia
        if !isClosed && !confirmedPts.isEmpty, let live = livePoint {
            let prev = confirmedPts.last!
            setColor(Self.COLOR_LIVE); glLineWidth(6)
            prim(GLenum(GL_LINES), [prev.x, drawY, prev.z, live.x, drawY, live.z], 2)
        }

        // ── 5c. Vertical edge at ghost corner
        if !isClosed, let live = livePoint,
           capturePhase != .awaitFirstFloor && capturePhase != .awaitHeight {
            let topY = drawY + wallHeight
            let vcx = topCursorPoint?.x ?? live.x
            let vcz = topCursorPoint?.z ?? live.z
            glEnable(GLenum(GL_BLEND)); glBlendFunc(GLenum(GL_SRC_ALPHA), GLenum(GL_ONE_MINUS_SRC_ALPHA))
            setColor([0.12, 0.85, 1.00, 0.75]); glLineWidth(3)
            prim(GLenum(GL_LINES), [vcx, drawY, vcz, vcx, topY, vcz], 2)
            glDisable(GLenum(GL_BLEND))
        }

        // ── 5e. Ceiling traces in TOP mode
        if let tcp = topCursorPoint, !isClosed,
           capturePhase != .awaitFirstFloor && capturePhase != .awaitHeight {
            let topY = drawY + wallHeight
            glEnable(GLenum(GL_BLEND)); glBlendFunc(GLenum(GL_SRC_ALPHA), GLenum(GL_ONE_MINUS_SRC_ALPHA))
            if confirmedPts.count >= 2 {
                var v = [Float]()
                for pt in confirmedPts { v += [pt.x, topY, pt.z] }
                setColor(Self.COLOR_CONFIRMED); glLineWidth(5)
                prim(GLenum(GL_LINE_STRIP), v, GLsizei(confirmedPts.count))
            }
            if !confirmedPts.isEmpty {
                let prev = confirmedPts.last!
                setColor(Self.COLOR_LIVE); glLineWidth(6)
                prim(GLenum(GL_LINES), [prev.x, topY, prev.z, tcp.x, topY, tcp.z], 2)
            }
            if !confirmedPts.isEmpty {
                var vd = [Float]()
                for pt in confirmedPts { vd += [pt.x, topY + 0.005, pt.z] }
                setColor(Self.COLOR_DOT)
                prim(GLenum(GL_POINTS), vd, GLsizei(confirmedPts.count))
            }
            glDisable(GLenum(GL_BLEND))
        }

        // ── 5d. Top cursor
        if let tcp = topCursorPoint { drawTopCursor(tcp.x, tcp.y, tcp.z) }

        // ── 5a. Goniometer
        if let gc = goniometerCenter {
            let gonioY: Float = {
                if let tcp = topCursorPoint { return tcp.y + Self.FLOOR_OFFSET }
                return (currentFloorY ?? gc.y) + Self.FLOOR_OFFSET
            }()
            let snapRayLen: Float = {
                guard let tcp = topCursorPoint else { return 0.50 }
                let dx = tcp.x - gc.x; let dz = tcp.z - gc.z
                return sqrt(dx*dx + dz*dz) + 0.30
            }()
            drawGoniometer(gc.x, gonioY, gc.z,
                           reticleAngle: goniometerAngle,
                           snapAngle: goniometerSnapAngle,
                           snapRayLen: snapRayLen)
        }

        // ── 5b. Ghost corner
        if !isClosed, let live = livePoint {
            drawGhostCorner(live.x, drawY, live.z, isSnapped: reticleSnapped)
        }

        // ── 6. Vertex dots
        if !confirmedPts.isEmpty {
            var v = [Float]()
            for pt in confirmedPts { v += [pt.x, drawY + 0.005, pt.z] }
            setColor(Self.COLOR_DOT)
            prim(GLenum(GL_POINTS), v, GLsizei(confirmedPts.count))
        }

        // ── 7. Wall skeleton post-close
        if isClosed && confirmedPts.count >= 3 {
            drawWallSkeleton(confirmedPts, drawY: drawY, wallH: wallHeight)
        }

        glUseProgram(0)
    }

    // MARK: – Wall fills

    private func drawConfirmedWallFills(_ pts: [SIMD3<Float>], drawY: Float, wallH: Float) {
        let topY = drawY + wallH; let seg = pts.count - 1
        var v = [Float](); v.reserveCapacity(seg * 18)
        for i in 0..<seg {
            let a = pts[i]; let b = pts[i + 1]
            v += [a.x, drawY, a.z,  b.x, drawY, b.z,  b.x, topY, b.z]
            v += [a.x, drawY, a.z,  b.x, topY,  b.z,  a.x, topY, a.z]
        }
        glEnable(GLenum(GL_BLEND)); glBlendFunc(GLenum(GL_SRC_ALPHA), GLenum(GL_ONE_MINUS_SRC_ALPHA))
        setColor(Self.COLOR_WALL_FILL)
        prim(GLenum(GL_TRIANGLES), v, GLsizei(seg * 6))
        glDisable(GLenum(GL_BLEND))
    }

    private func drawLiveWallFill(_ prev: SIMD3<Float>, _ live: SIMD3<Float>, drawY: Float, wallH: Float) {
        let topY = drawY + wallH
        let v: [Float] = [
            prev.x, drawY, prev.z,  live.x, drawY, live.z,  live.x, topY, live.z,
            prev.x, drawY, prev.z,  live.x, topY,  live.z,  prev.x, topY, prev.z
        ]
        glEnable(GLenum(GL_BLEND)); glBlendFunc(GLenum(GL_SRC_ALPHA), GLenum(GL_ONE_MINUS_SRC_ALPHA))
        setColor(Self.COLOR_LIVE_WALL_FILL)
        prim(GLenum(GL_TRIANGLES), v, 6)
        glDisable(GLenum(GL_BLEND))
    }

    private func drawWallGridLines(_ pts: [SIMD3<Float>], drawY: Float, wallH: Float) {
        let step: Float = 0.30; let topY = drawY + wallH; let seg = pts.count - 1
        var lines = [Float]()
        var gy = drawY + step
        while gy < topY - 0.05 {
            for i in 0..<seg {
                let a = pts[i]; let b = pts[i + 1]
                lines += [a.x, gy, a.z, b.x, gy, b.z]
            }
            gy += step
        }
        for i in 0..<seg {
            let a = pts[i]; let b = pts[i + 1]
            let dx = b.x - a.x; let dz = b.z - a.z
            let segLen = sqrt(dx*dx + dz*dz); guard segLen >= 0.01 else { continue }
            let ndx = dx / segLen; let ndz = dz / segLen
            var t: Float = step
            while t < segLen - 0.05 {
                let gx = a.x + ndx * t; let gz = a.z + ndz * t
                lines += [gx, drawY, gz, gx, topY, gz]
                t += step
            }
        }
        guard !lines.isEmpty else { return }
        glEnable(GLenum(GL_BLEND)); glBlendFunc(GLenum(GL_SRC_ALPHA), GLenum(GL_ONE_MINUS_SRC_ALPHA))
        setColor(Self.COLOR_WALL_GRID); glLineWidth(2)
        prim(GLenum(GL_LINES), lines, GLsizei(lines.count / 3))
        glDisable(GLenum(GL_BLEND))
    }

    // MARK: – Height preview

    private func drawHeightPreview(_ p0: SIMD3<Float>, drawY: Float, heightM: Float) {
        let x = p0.x; let z = p0.z; let topY = drawY + heightM
        let aw: Float = 0.08; let ah: Float = 0.07
        setColor(Self.COLOR_HEIGHT_LINE); glLineWidth(5)
        prim(GLenum(GL_LINES), [x, drawY, z, x, topY, z], 2)
        glLineWidth(3)
        prim(GLenum(GL_LINES), [x - aw, topY, z, x + aw, topY, z], 2)
        prim(GLenum(GL_LINES), [
            x - aw * 0.6, topY - ah, z, x, topY, z,
            x + aw * 0.6, topY - ah, z, x, topY, z
        ], 4)
        prim(GLenum(GL_LINES), [
            x - aw * 0.6, drawY + ah, z, x, drawY, z,
            x + aw * 0.6, drawY + ah, z, x, drawY, z
        ], 4)
    }

    // MARK: – Ghost corner

    private func drawGhostCorner(_ x: Float, _ baseY: Float, _ z: Float, isSnapped: Bool) {
        let y = baseY + 0.004; let arm: Float = 0.08; let dia: Float = 0.06
        setColor(isSnapped ? Self.COLOR_CONFIRMED : Self.COLOR_GHOST_CORNER)
        glLineWidth(6)
        prim(GLenum(GL_LINES), [x - arm, y, z, x + arm, y, z, x, y, z - arm, x, y, z + arm], 4)
        glLineWidth(3)
        prim(GLenum(GL_LINES), [
            x, y, z - dia,  x + dia, y, z,
            x + dia, y, z,  x, y, z + dia,
            x, y, z + dia,  x - dia, y, z,
            x - dia, y, z,  x, y, z - dia
        ], 8)
    }

    // MARK: – Top cursor (TOP mode)

    private func drawTopCursor(_ x: Float, _ y: Float, _ z: Float) {
        let ty = y + 0.004; let arm: Float = 0.08; let dia: Float = 0.06
        glEnable(GLenum(GL_BLEND)); glBlendFunc(GLenum(GL_SRC_ALPHA), GLenum(GL_ONE_MINUS_SRC_ALPHA))
        setColor(Self.COLOR_TOP_CURSOR); glLineWidth(6)
        prim(GLenum(GL_LINES), [x - arm, ty, z, x + arm, ty, z, x, ty, z - arm, x, ty, z + arm], 4)
        glLineWidth(3)
        prim(GLenum(GL_LINES), [
            x, ty, z - dia,  x + dia, ty, z,
            x + dia, ty, z,  x, ty, z + dia,
            x, ty, z + dia,  x - dia, ty, z,
            x - dia, ty, z,  x, ty, z - dia
        ], 8)
        glDisable(GLenum(GL_BLEND))
    }

    // MARK: – Axis hints

    private func drawAxisHints(_ last: SIMD3<Float>, _ prev: SIMD3<Float>, drawY: Float) {
        let dx = last.x - prev.x; let dz = last.z - prev.z
        let len = sqrt(dx*dx + dz*dz); guard len >= 0.01 else { return }
        let nx = dx / len; let nz = dz / len; let px = -nz; let pz = nx
        let arm: Float = 1.2; let y = drawY + 0.003
        let bx = last.x; let bz = last.z
        setColor(Self.COLOR_AXIS_HINT); glLineWidth(2)
        prim(GLenum(GL_LINES), [
            bx, y, bz,  bx + nx*arm, y, bz + nz*arm,
            bx, y, bz,  bx + px*arm, y, bz + pz*arm,
            bx, y, bz,  bx - px*arm, y, bz - pz*arm
        ], 6)
    }

    // MARK: – Wall skeleton (post-close)

    private func drawWallSkeleton(_ pts: [SIMD3<Float>], drawY: Float, wallH: Float) {
        let topY = drawY + wallH; let n = pts.count
        var v = [Float](); v.reserveCapacity(n * 12)
        for pt in pts { v += [pt.x, drawY, pt.z, pt.x, topY, pt.z] }
        for i in 0..<n {
            let a = pts[i]; let b = pts[(i + 1) % n]
            v += [a.x, topY, a.z, b.x, topY, b.z]
        }
        setColor(Self.COLOR_WALL_PREVIEW); glLineWidth(2)
        prim(GLenum(GL_LINES), v, GLsizei(v.count / 3))
    }

    // MARK: – Goniometer (invariante assoluta: ±65°, isteresi esterna)

    private func drawGoniometer(_ cx: Float, _ y: Float, _ cz: Float,
                                 reticleAngle: Float, snapAngle: Float?,
                                 snapRayLen: Float) {
        let PI_F:       Float = .pi
        let DEG1_RAD:   Float = PI_F / 180
        let STEP1:      Float = DEG1_RAD
        let STEP5:      Float = DEG1_RAD * 5
        let STEP10:     Float = DEG1_RAD * 10
        let SECTOR:     Float = DEG1_RAD * 65
        let R_OUT:      Float = 0.40
        let R_IN:       Float = 0.17
        let y2                = y + 0.003
        let CYAN70:  [Float]  = [0.12, 0.85, 1.00, 0.70]
        let CYAN100: [Float]  = [0.12, 0.85, 1.00, 1.00]

        let startA = floor((reticleAngle - SECTOR) / STEP10) * STEP10
        let endA   = startA + SECTOR * 2 + STEP10

        glEnable(GLenum(GL_BLEND)); glBlendFunc(GLenum(GL_SRC_ALPHA), GLenum(GL_ONE_MINUS_SRC_ALPHA))
        setColor(CYAN70)

        // Arco esterno
        var outer = [Float](); var a = startA
        while a <= endA + STEP1 * 0.5 {
            outer += [cx + cos(a) * R_OUT, y2, cz + sin(a) * R_OUT]; a += STEP1
        }
        glLineWidth(2); prim(GLenum(GL_LINE_STRIP), outer, GLsizei(outer.count / 3))

        // Arco interno
        var inner = [Float](); a = startA
        while a <= endA + STEP1 * 0.5 {
            inner += [cx + cos(a) * R_IN, y2, cz + sin(a) * R_IN]; a += STEP1
        }
        glLineWidth(1); prim(GLenum(GL_LINE_STRIP), inner, GLsizei(inner.count / 3))

        // Raggi ogni 10°
        var spokes = [Float](); a = startA
        while a <= endA {
            spokes += [cx + cos(a) * R_IN,  y2, cz + sin(a) * R_IN]
            spokes += [cx + cos(a) * R_OUT, y2, cz + sin(a) * R_OUT]
            a += STEP10
        }
        glLineWidth(1); prim(GLenum(GL_LINES), spokes, GLsizei(spokes.count / 3))

        // Tacche arco esterno
        var ticks = [Float](); a = startA
        while a <= endA {
            let degRaw = Int((a * 180 / PI_F).rounded())
            let deg    = ((degRaw % 360) + 360) % 360
            let tickLen: Float = (deg % 10 == 0) ? 0 : (deg % 5 == 0) ? 0.04 : 0.02
            if tickLen > 0 {
                ticks += [cx + cos(a) * R_OUT, y2, cz + sin(a) * R_OUT]
                ticks += [cx + cos(a) * (R_OUT + tickLen), y2, cz + sin(a) * (R_OUT + tickLen)]
            }
            a += STEP1
        }
        if !ticks.isEmpty { glLineWidth(1); prim(GLenum(GL_LINES), ticks, GLsizei(ticks.count / 3)) }

        // Tacche arco interno
        var iticks = [Float](); a = startA
        while a <= endA {
            let degRaw = Int((a * 180 / PI_F).rounded())
            let deg    = ((degRaw % 360) + 360) % 360
            let tickLen: Float = (deg % 10 == 0) ? 0 : (deg % 5 == 0) ? 0.03 : 0
            if tickLen > 0 {
                iticks += [cx + cos(a) * R_IN, y2, cz + sin(a) * R_IN]
                iticks += [cx + cos(a) * (R_IN - tickLen), y2, cz + sin(a) * (R_IN - tickLen)]
            }
            a += STEP5
        }
        if !iticks.isEmpty { glLineWidth(1); prim(GLenum(GL_LINES), iticks, GLsizei(iticks.count / 3)) }

        // Raggio snap
        if let sa = snapAngle {
            setColor(CYAN100); glLineWidth(3)
            prim(GLenum(GL_LINES), [
                cx, y2, cz,
                cx + cos(sa) * snapRayLen, y2, cz + sin(sa) * snapRayLen
            ], 2)
        }

        // Punto centrale
        setColor(CYAN100); prim(GLenum(GL_POINTS), [cx, y2 + 0.002, cz], 1)
        glDisable(GLenum(GL_BLEND))
    }

    // MARK: – GL helpers

    private func setColor(_ c: [Float]) {
        glUniform4f(uColorLoc, c[0], c[1], c[2], c[3])
    }

    private func prim(_ mode: GLenum, _ verts: [Float], _ count: GLsizei) {
        verts.withUnsafeBufferPointer { buf in
            glEnableVertexAttribArray(GLuint(aPosLoc))
            glVertexAttribPointer(GLuint(aPosLoc), 3, GLenum(GL_FLOAT), GLboolean(GL_FALSE), 0, buf.baseAddress)
            glDrawArrays(mode, 0, count)
            glDisableVertexAttribArray(GLuint(aPosLoc))
        }
    }

    private func matToArray(_ m: simd_float4x4) -> [Float] {
        [m.columns.0.x, m.columns.0.y, m.columns.0.z, m.columns.0.w,
         m.columns.1.x, m.columns.1.y, m.columns.1.z, m.columns.1.w,
         m.columns.2.x, m.columns.2.y, m.columns.2.z, m.columns.2.w,
         m.columns.3.x, m.columns.3.y, m.columns.3.z, m.columns.3.w]
    }

    private func compileShader(_ type: GLenum, _ src: String) -> GLuint {
        let id = glCreateShader(type)
        src.withCString { glShaderSource(id, 1, [$0], nil) }
        glCompileShader(id)
        return id
    }
}
