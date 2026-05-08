import GLKit
import simd

/// Port 1:1 di OpeningRenderer.kt — highlight muri e aperture in GLES 2.0.
class OpeningRenderer {

    // MARK: – Colori

    private static let COLOR_HOVER_FILL:    [Float] = [0.00, 0.90, 1.00, 0.18]
    private static let COLOR_HOVER_OUTLINE: [Float] = [0.00, 0.90, 1.00, 1.00]
    private static let COLOR_SEL_FILL:      [Float] = [0.85, 0.10, 0.95, 0.28]
    private static let COLOR_SEL_OUTLINE:   [Float] = [0.95, 0.20, 1.00, 1.00]
    private static let COLOR_DOOR:          [Float] = [0.90, 0.55, 0.10, 0.80]
    private static let COLOR_WINDOW:        [Float] = [0.30, 0.70, 1.00, 0.75]
    private static let COLOR_FRENCH_DOOR:   [Float] = [0.60, 0.30, 0.90, 0.80]
    private static let COLOR_BORDER:        [Float] = [1.00, 1.00, 1.00, 1.00]

    private static let VERT_SRC = """
        uniform mat4 uMVP;
        attribute vec4 aPos;
        void main() { gl_Position = uMVP * aPos; }
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

    /// - Parameter baseY: world Y del pavimento (es. -0.93). Model Y=0 = pavimento.
    func draw(
        roomModel:      RoomModel,
        baseY:          Float,
        hoveredWallId:  String?,
        selectedWallId: String?,
        viewMatrix:     simd_float4x4,
        projMatrix:     simd_float4x4
    ) {
        guard program != 0 else { return }

        let mvp = matToArray(projMatrix * viewMatrix)
        glEnable(GLenum(GL_BLEND))
        glBlendFunc(GLenum(GL_SRC_ALPHA), GLenum(GL_ONE_MINUS_SRC_ALPHA))
        glUseProgram(program)
        mvp.withUnsafeBufferPointer { glUniformMatrix4fv(uMVPLoc, 1, GLboolean(GL_FALSE), $0.baseAddress) }

        for wall in roomModel.walls {
            switch wall.id {
            case selectedWallId:
                drawWallHighlight(wall, baseY: baseY,
                                  fill: Self.COLOR_SEL_FILL,   outline: Self.COLOR_SEL_OUTLINE,   lineW: 7)
            case hoveredWallId:
                drawWallHighlight(wall, baseY: baseY,
                                  fill: Self.COLOR_HOVER_FILL, outline: Self.COLOR_HOVER_OUTLINE, lineW: 5)
            default:
                break
            }
            for opening in wall.openings {
                drawOpening(wall, baseY: baseY, opening: opening)
            }
        }

        glDisable(GLenum(GL_BLEND))
        glUseProgram(0)
    }

    // MARK: – Wall highlight

    private func drawWallHighlight(
        _ wall: WallModel, baseY: Float,
        fill: [Float], outline: [Float], lineW: Float
    ) {
        let sx = wall.start.x; let sz = wall.start.z
        let ex = wall.end.x;   let ez = wall.end.z
        let yF = baseY; let yT = baseY + wall.height

        // Fill semitrasparente
        setColor(fill)
        prim(GLenum(GL_TRIANGLE_FAN), [sx, yF, sz, ex, yF, ez, ex, yT, ez, sx, yT, sz], 4)

        // Outline: 3 lati (sinistro, top, destro) — bordo a terra omesso
        setColor(outline); glLineWidth(lineW)
        prim(GLenum(GL_LINE_STRIP), [sx, yF, sz, sx, yT, sz, ex, yT, ez, ex, yF, ez], 4)
    }

    // MARK: – Opening box

    private func drawOpening(_ wall: WallModel, baseY: Float, opening: OpeningModel) {
        let dx = wall.dirX; let dz = wall.dirZ

        let x0 = wall.start.x + dx * opening.offsetAlongWall
        let z0 = wall.start.z + dz * opening.offsetAlongWall
        let x1 = wall.start.x + dx * (opening.offsetAlongWall + opening.width)
        let z1 = wall.start.z + dz * (opening.offsetAlongWall + opening.width)
        let yB = baseY + opening.bottom
        let yT = baseY + opening.bottom + opening.height

        let fillColor: [Float] = {
            switch opening.kind {
            case .door:       return Self.COLOR_DOOR
            case .window:     return Self.COLOR_WINDOW
            case .frenchDoor: return Self.COLOR_FRENCH_DOOR
            }
        }()

        glEnable(GLenum(GL_POLYGON_OFFSET_FILL))
        glPolygonOffset(-2, -2)
        setColor(fillColor)
        prim(GLenum(GL_TRIANGLE_FAN), [x0, yB, z0, x1, yB, z1, x1, yT, z1, x0, yT, z0], 4)
        glDisable(GLenum(GL_POLYGON_OFFSET_FILL))

        setColor(Self.COLOR_BORDER); glLineWidth(3)
        prim(GLenum(GL_LINE_LOOP), [x0, yB, z0, x1, yB, z1, x1, yT, z1, x0, yT, z0], 4)

        // Lineetta al centro base
        let cx = (x0 + x1) / 2; let cz = (z0 + z1) / 2
        glLineWidth(2)
        prim(GLenum(GL_LINES), [cx, yB - 0.04, cz, cx, yB + 0.04, cz], 2)
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
