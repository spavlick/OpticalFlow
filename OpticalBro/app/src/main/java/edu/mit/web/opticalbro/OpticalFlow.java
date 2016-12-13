package edu.mit.web.opticalbro;

import android.content.Context;
import android.graphics.Bitmap;
import android.renderscript.Script;
import android.renderscript.Type;
import android.renderscript.Allocation;
import android.renderscript.Element;
import android.renderscript.RenderScript;
import android.renderscript.ScriptC;
import android.renderscript.ScriptIntrinsic;
import android.renderscript.ScriptIntrinsicResize;

/**
 * Created by Steven on 12/12/2016.
 */

public class OpticalFlow {

    public static void calculateAverages(Context context,  float[] u, float[] v, float[] uAvg, float[] vAvg, int width, int height) {
        RenderScript rs = RenderScript.create(context);

        int size = width*height;

        Allocation input_u = Allocation.createTyped(rs, Type.createXY(rs, Element.F32(rs), width, height));
        Allocation input_v = Allocation.createTyped(rs, Type.createXY(rs, Element.F32(rs), width, height));
        Allocation output_uAvg = Allocation.createTyped(rs, Type.createXY(rs, Element.F32(rs), width, height));
        Allocation output_vAvg = Allocation.createTyped(rs, Type.createXY(rs, Element.F32(rs), width, height));

        // copy the inputs to that memory
        output_uAvg.copy2DRangeFrom(0, 0, width, height, uAvg);
        output_vAvg.copy2DRangeFrom(0, 0, width, height, vAvg);
        input_u.copy2DRangeFrom(0, 0, width, height, u);
        input_v.copy2DRangeFrom(0, 0, width, height, v);

        // prepare the renderscript
        ScriptC_foo foo = new ScriptC_foo(rs);
        foo.set_height(height);
        foo.set_width(width);

        // first calculate u
        foo.set_velocities_in(input_u);
        foo.forEach_CalculateAverages(output_uAvg);
        // then v
        foo.set_velocities_in(input_v);
        foo.forEach_CalculateAverages(output_vAvg);

        // copy the results back to the java arrays
        output_uAvg.copy2DRangeTo(0, 0, width, height, uAvg);
        output_vAvg.copy2DRangeTo(0, 0, width, height, vAvg);

        foo.destroy();

    }
    public static void adjust(Context context, float[]u, float[]v, float[] uAvg, float[] vAvg, int[] E_x, int[] E_y, int[] E_t, int width, int height) {

        // initialize the renderscript object that will run the GPU code
        RenderScript rs = RenderScript.create(context);

        int size = width*height;

        // allocate the memory that will be processed in renderscript by the GPU
        Allocation input_uAvg = Allocation.createTyped(rs, Type.createXY(rs, Element.F32(rs), width, height));
        Allocation input_vAvg = Allocation.createTyped(rs, Type.createXY(rs, Element.F32(rs), width, height));
        Allocation input_E_x = Allocation.createTyped(rs, Type.createXY(rs, Element.I32(rs), width, height));
        Allocation input_E_y = Allocation.createTyped(rs, Type.createXY(rs, Element.I32(rs), width, height));
        Allocation input_E_t = Allocation.createTyped(rs, Type.createXY(rs, Element.I32(rs), width, height));
        Allocation output_u = Allocation.createTyped(rs, Type.createXY(rs, Element.F32(rs), width, height));
        Allocation output_v = Allocation.createTyped(rs, Type.createXY(rs, Element.F32(rs), width, height));

        // copy the inputs to that memory
        input_uAvg.copy2DRangeFrom(0, 0, width, height, uAvg);
        input_vAvg.copy2DRangeFrom(0, 0, width, height, vAvg);
        input_E_x.copy2DRangeFrom(0, 0, width, height, E_x);
        input_E_y.copy2DRangeFrom(0, 0, width, height, E_y);
        input_E_t.copy2DRangeFrom(0, 0, width, height, E_t);
        output_u.copy2DRangeFrom(0, 0, width, height, u);
        output_v.copy2DRangeFrom(0, 0, width, height, v);

        // run the renderscript
        ScriptC_foo foo = new ScriptC_foo(rs);
        // calculate the adjustments for u
        foo.forEach_Adjust(input_uAvg, input_vAvg, input_E_x, input_E_y, input_E_t, output_u);
        // same calculation, but from v's perspective
        foo.forEach_Adjust(input_vAvg, input_uAvg, input_E_y, input_E_x, input_E_t, output_v);

        // copy the results back to the java arrays
        output_u.copy2DRangeTo(0, 0, width, height, u);
        output_v.copy2DRangeTo(0, 0, width, height, v);

        foo.destroy();

        // Initialize the guesses for optical flow as 0 everywhere
        /*u = new float[mImageHeight-1][mImageWidth-1];
        v = new float[mImageHeight-1][mImageWidth-1];
        // note: don't need to reset uAvg+vAvg b/c they are immediately set below based on u+v
        uAvg = new float[mImageHeight-1][mImageWidth-1];
        vAvg = new float[mImageHeight-1][mImageWidth-1];

        // Calculate the optical flow using an iterative scheme
        for (int iterations = 0; iterations < 4; iterations++) {
            // first, calculate the averages
            for (int j = 0; j < mImageHeight-1; j++) {
                for (int i = 0; i < mImageWidth-1; i++) {
                    uAvg[j][i] = getNeighborAverage(u, i, j, mImageWidth-1, mImageHeight-1);
                    vAvg[j][i] = getNeighborAverage(v, i, j, mImageWidth-1, mImageHeight-1);
                }
            }

            // Then, calculate the new estimate for the velocities
            for (int j = 0; j < mImageHeight-1; j++) {
                for (int i = 0; i < mImageWidth-1; i++) {
                    float adjustment = (E_x[j][i] * uAvg[j][i] + E_y[j][i] * vAvg[j][i] + E_t[j][i]) / (1 + lambda * ((float)Math.pow(E_x[j][i], 2) + (float)Math.pow(E_y[j][i],2)));
                    uAvg[j][i] = u[j][i] - E_x[j][i] * adjustment;
                    vAvg[j][i] = v[j][i] - E_y[j][i] * adjustment;
                }
            }
        }*/
    }
}
