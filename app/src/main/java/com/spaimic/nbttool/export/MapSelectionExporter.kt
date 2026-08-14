package com.spaimic.nbttool.export

import android.graphics.Bitmap
import android.graphics.Color
import com.spaimic.nbttool.chunk.BlockColors
import com.spaimic.nbttool.chunk.ChunkScanner
import com.spaimic.nbttool.chunk.ColumnInfo
import com.spaimic.nbttool.selection.WorldSelection
import com.spaimic.nbttool.shizuku.LevelDbBridge
import java.io.BufferedOutputStream
import java.io.File
import java.io.OutputStream
import java.util.zip.Deflater

object MapSelectionExporter {
    private const val CHUNK=16

    fun export(
        bridge: LevelDbBridge,
        selection: WorldSelection,
        dimension: Int,
        pixelsPerBlock: Int,
        file: File,
        onProgress: (Int)->Unit = {}
    ) {
        require(selection.isValid)
        require(pixelsPerBlock in 1..24)
        val width=selection.width*pixelsPerBlock
        val height=selection.height*pixelsPerBlock
        PngWriter(file.outputStream(),width,height).use { png ->
            renderRows(bridge,selection,dimension,pixelsPerBlock){ row -> png.writeRow(row) }
            onProgress(100)
        }
    }

    fun preview(
        bridge: LevelDbBridge,
        selection: WorldSelection,
        dimension: Int,
        requestedPixelsPerBlock: Int,
        maxPixels: Int=768
    ): Bitmap {
        val ppb=minOf(requestedPixelsPerBlock.coerceIn(1,24), maxOf(1,maxPixels/maxOf(selection.width,selection.height)))
        val w=selection.width*ppb
        val h=selection.height*ppb
        val bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
        val colors=IntArray(w)
        var outY=0
        renderRows(bridge,selection,dimension,ppb){ row ->
            for (x in row.indices) colors[x]=row[x]
            repeat(ppb) {
                bitmap.setPixels(colors,0,w,0,outY,w,1)
                outY++
            }
        }
        return bitmap
    }

    private fun renderRows(
        bridge: LevelDbBridge, s: WorldSelection, dimension: Int, ppb: Int,
        consume:(IntArray)->Unit
    ) {
        val unknown=HashSet<String>(); val reasons=HashMap<String,Int>(); val examples=HashMap<String,String>()
        val chunkMinX=Math.floorDiv(s.minX,16); val chunkMaxX=Math.floorDiv(s.maxX,16)
        val chunkMinZ=Math.floorDiv(s.minZ,16); val chunkMaxZ=Math.floorDiv(s.maxZ,16)
        val rowWidth=s.width*ppb
        val rgb=IntArray(rowWidth)
        for(cz in chunkMinZ..chunkMaxZ) {
            val grids=HashMap<Int,Array<Array<ColumnInfo>>>()
            for(cx in chunkMinX..chunkMaxX) {
                val loaded=ChunkScanner.loadChunkDirect(bridge,cx,cz,unknown,reasons,examples,dimension,includeMarkers=false)
                if(loaded!=null) grids[cx]=loaded.grid
            }
            val z0=maxOf(s.minZ,cz*16); val z1=minOf(s.maxZ,cz*16+15)
            for(wz in z0..z1) {
                var cursor=0
                for(wx in s.minX..s.maxX) {
                    val cx=Math.floorDiv(wx,16); val lx=Math.floorMod(wx,16)
                    val lz=Math.floorMod(wz,16)
                    val color=grids[cx]?.get(lx)?.get(lz)?.color ?: BlockColors.fallbackUnloaded
                    repeat(ppb){rgb[cursor++]=color}
                }
                repeat(ppb){ consume(rgb.copyOf()) }
            }
        }
    }

    private class PngWriter(raw:OutputStream,val width:Int,val height:Int):AutoCloseable {
        private val out=BufferedOutputStream(raw,64*1024)
        private val deflater=Deflater(Deflater.BEST_SPEED)
        private var rows=0
        private val buf=ByteArray(1024*1024)
        init {
            out.write(byteArrayOf(137.toByte(),80,78,71,13,10,26,10))
            val ihdr=ByteArray(13)
            writeInt(ihdr,0,width); writeInt(ihdr,4,height); ihdr[8]=8; ihdr[9]=2
            writeChunk("IHDR",ihdr)
            deflater.setInput(ByteArray(0))
        }
        fun writeRow(colors:IntArray) {
            val raw=ByteArray(colors.size*3+1); raw[0]=0
            var j=1
            for(c in colors){raw[j++]=Color.red(c).toByte();raw[j++]=Color.green(c).toByte();raw[j++]=Color.blue(c).toByte()}
            if(rows==0) deflater.reset()
            deflater.setInput(raw)
            while(!deflater.needsInput()){
                val n=deflater.deflate(buf)
                if(n>0) writeChunk("IDAT",buf,n)
                else break
            }
            rows++
        }
        override fun close(){
            deflater.finish()
            while(!deflater.finished()){
                val n=deflater.deflate(buf)
                if(n>0) writeChunk("IDAT",buf,n) else break
            }
            writeChunk("IEND",ByteArray(0))
            out.flush(); out.close(); deflater.end()
        }
        private fun writeChunk(type:String,data:ByteArray,len:Int=data.size){
            val tb=type.toByteArray(Charsets.US_ASCII)
            val actual=if(len==data.size)data else data.copyOf(len)
            writeInt(actual.size)
            out.write(tb);out.write(actual)
            val crc=java.util.zip.CRC32();crc.update(tb);crc.update(actual)
            writeInt(crc.value.toInt())
        }
        private fun writeInt(v:Int){
            out.write((v ushr 24) and 255);out.write((v ushr 16) and 255);out.write((v ushr 8) and 255);out.write(v and 255)
        }
        private fun writeInt(a:ByteArray,o:Int,v:Int){a[o]=(v ushr 24).toByte();a[o+1]=(v ushr 16).toByte();a[o+2]=(v ushr 8).toByte();a[o+3]=v.toByte()}
    }
}
