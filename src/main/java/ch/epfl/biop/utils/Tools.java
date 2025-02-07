package ch.epfl.biop.utils;

import ij.IJ;
import ij.ImagePlus;
import ij.gui.Roi;
import ij.process.FloatProcessor;
import ij.process.ImageStatistics;
import org.apache.commons.math3.stat.correlation.PearsonsCorrelation;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class Tools {
    final private static int HEAT_MAP_SCALED_SIZE = 256;
    final private static String HEAT_MAP_BIT_DEPTH = "32-bit black";

    final public static String SEPARATION_CHARACTER = "%";
    final public static String PROCESSED_IMAGES_SUFFIX = "processed_images";
    final public static String PARENT_TABLE_SUFFIX = "table";
    final public static String PROCESSED_FEATURE = "feature";
    final public static String FWHM_TAG = "fwhm";
    final public static String FIELD_DISTORTION_TAG = "field_distortion";
    final public static String FIELD_UNIFORMITY_TAG = "field_uniformity";
    final public static String ARGOLIGHT_TAG = "argolight";
    final public static String RAW_TAG = "raw";
    final public static String PROCESSED_TAG = "processed";

    final public static String FULL_FOV = "fullFoV";
    final public static String PARTIAL_FOV = "partialFoV";

    /**
     * Generate the current date and hour in the format aaaammdd-hhHmmMss
     * @return
     */
    public static String getCurrentDateAndHour(){
        LocalDateTime localDateTime = LocalDateTime.now();
        LocalTime localTime = localDateTime.toLocalTime();
        LocalDate localDate = localDateTime.toLocalDate();
        return String.valueOf(localDate.getYear())+
                (localDate.getMonthValue() < 10 ? "0"+localDate.getMonthValue():localDate.getMonthValue()) +
                (localDate.getDayOfMonth() < 10 ? "0"+localDate.getDayOfMonth():localDate.getDayOfMonth())+"-"+
                (localTime.getHour() < 10 ? "0"+localTime.getHour():localTime.getHour())+"h"+
                (localTime.getMinute() < 10 ? "0"+localTime.getMinute():localTime.getMinute())+"m"+
                (localTime.getSecond() < 10 ? "0"+localTime.getSecond():localTime.getSecond());

    }
    public static String getErrorStackTraceAsString(Exception e){
        return Arrays.stream(e.getStackTrace()).map(StackTraceElement::toString).reduce("",(a, b)->a + "     at "+b+"\n");
    }


    /**
     * Create and save a new csv file.
     * It overwrites existing file
     *
     * @param file
     * @param text
     * @return
     */
    public static boolean saveCsvFile(File file, String text){
        try {
            try (BufferedWriter bw = new BufferedWriter(new FileWriter(file))) {
                bw.write(text);
                IJLogger.info("Saving csv file","File successfully saved in " + file.getAbsolutePath());
                return true;
            }
        }catch (IOException e){
            IJLogger.error("Saving csv file", "Error when saving csv in "+file.getAbsolutePath(), e);
            return false;
        }
    }

    /**
     * read a csv file
     *
     * @param table
     * @return
     */
    public static List<String> readCsvFile(File table){
        try{
            List<String> rows = new ArrayList<>();
            try (BufferedReader br = new BufferedReader(new FileReader(table))) {
                String line = "";
                while ((line = br.readLine()) != null)
                    rows.add(line);
            }
            return rows;
        }catch (IOException e){
            IJLogger.error("Reading csv file", "Error when reading csv "+table.getAbsolutePath(), e);
            return Collections.emptyList();
        }
    }


    /**
     * write at the end of an existing file without deleting previous data
     * @param file
     * @param text
     * @return
     */
    public static boolean appendCsvFile(File file, String text){
        try {
            try (BufferedWriter bw = new BufferedWriter(new FileWriter(file, true))) {
                bw.write(text);
                return true;
            }
        }catch (IOException e){
            IJLogger.error("Updating csv File", "Error when trying to update csv file in "+file.getAbsolutePath(), e);
            return false;
        }
    }

    /**
     * Compute the mean, max, min and std of a series of values
     *
     * @param values
     * @return
     */
    public static double[] computeStatistics(List<Double> values){
        if(values.isEmpty())
            return new double[]{0, 0, 0, 0};
        // average value
        double average = values.stream().reduce(0.0, Double::sum) / values.size();

        // std value
        List<Double> stdList = new ArrayList<>();
        values.forEach(e->stdList.add(Math.pow(e - average,2)));
        double std = Math.sqrt(stdList.stream().reduce(0.0, Double::sum)/values.size());

        // min value
        double min = values.stream().min(Comparator.naturalOrder()).get();
        // max value
        double max = values.stream().max(Comparator.naturalOrder()).get();

        return new double[]{average, std, min, max};
    }

    /**
     * Generate an image of a measured metric.
     * The number of value for this metrics should satisfy sqrt(nValue + 1) = integer value
     *
     * @param values of the metric
     * @param title of the heatmap
     * @return the corresponding imagePlus
     */
    public static ImagePlus computeHeatMap(List<Double> values, String title, int imgWidth, int imgHeight, Roi cross,
                                           double rotation, double pixelSize, int argoSpacing){
        int nPoints = (int) Math.sqrt(values.size() + 1);
        ImagePlus imp = IJ.createImage(title, HEAT_MAP_BIT_DEPTH, nPoints, nPoints, 1);

        // set to each pixel the value for one ring
        values.add((int)Math.floor(values.size()/2.0), Double.NaN); // here we have a O in the center, because we didn't measure anything there
        FloatProcessor fp = new FloatProcessor(nPoints, nPoints, values.stream().mapToDouble(Double::doubleValue).toArray());
        values.remove((int)Math.floor(values.size()/2.0)); // remove that value
        imp.getProcessor().setPixels(1, fp);
        ImagePlus heatMapScaledImp = IJ.createImage(title, HEAT_MAP_BIT_DEPTH, HEAT_MAP_SCALED_SIZE*imgWidth/imgHeight, HEAT_MAP_SCALED_SIZE, 1);

        // compute the percentage of the target on the full image
        double ratio = (nPoints * argoSpacing / pixelSize) / imgWidth;

        // compute the final width/height of the heatmap on the final image
        int targetScaledSize = (int)(heatMapScaledImp.getWidth() * ratio);

        // enlarged the target heat map to have a decent image size at the end
        ImagePlus enlarged_imp = imp.resize(Math.max(targetScaledSize, 1), Math.max(targetScaledSize, 1), "none");

        // get pre-computed scaling factors
        double cos = Math.cos(rotation);
        double sin = Math.sin(rotation);
        double halfSize = targetScaledSize/2.0;
        ImageStatistics crossStat = cross.getStatistics();
        double scaledCrossX = crossStat.xCentroid * heatMapScaledImp.getWidth() / imgWidth;
        double scaledCrossY = crossStat.yCentroid * heatMapScaledImp.getHeight() / imgHeight;

        for(int i = 0; i < enlarged_imp.getWidth(); i++){
            for(int j = 0; j < enlarged_imp.getHeight(); j++){
                // compute the coordinates in the final image
                int transformX = (int)((i - halfSize)*cos - (j - halfSize)*sin + scaledCrossX);
                int transformY = (int)((i - halfSize)*sin + (j - halfSize)*cos + scaledCrossY);
                // fill the final image
                if(transformX >= 0 && transformX < heatMapScaledImp.getWidth() && transformY >= 0 && transformY < heatMapScaledImp.getHeight()){
                    heatMapScaledImp.getProcessor().putPixelValue(transformX, transformY, enlarged_imp.getProcessor().getPixelValue(i,j));
                }
            }
        }

        heatMapScaledImp.setTitle(title);

        // color the heat map with a lookup table
        IJ.run(heatMapScaledImp, "Fire", "");
        heatMapScaledImp.show();

        return heatMapScaledImp;
    }

    /**
     * compute the Pearson Correlation Coefficient between two images, given a list of regions where to compute it.
     *
     * @param imp1
     * @param imp2
     * @param rois
     * @return
     */
    public static List<Double> computePCC(ImagePlus imp1, ImagePlus imp2, List<Roi> rois){
        List<ImagePlus> imp1Crops = new ArrayList<>();
        List<ImagePlus> imp2Crops = new ArrayList<>();

        // crop images with ROIs' bounding boxes
        rois.forEach(roi->{
            imp1.setRoi(roi);
            imp1Crops.add(imp1.crop());
            imp2.setRoi(roi);
            imp2Crops.add(imp2.crop());
        });

        return computePCC(imp1Crops, imp2Crops);
    }

    /**
     * compute the Pearson Correlation Coefficient between two lists of images.
     * One PCC is computed for each pair of images ==> lists must have the same length.
     *
     * @param impList1
     * @param impList2
     * @return
     */
    private static List<Double> computePCC(List<ImagePlus> impList1, List<ImagePlus> impList2){
        // check lists' length
        if(impList1.size() != impList2.size()) {
            IJLogger.error("computePCC","Images lists do not have the same size :  "+impList1.size()+" vs " +impList2.size());
            return new ArrayList<>();
        }

        // for each patch, compute PCC
        List<Double> pccList = new ArrayList<>();
        for(int i = 0; i < impList1.size(); i++)
            pccList.add(computePCC(impList1.get(i),impList2.get(i)));

        return pccList;
    }


    /**
     * compute the Pearson Correlation Coefficient between two images.
     * Images must have the same dimensions.
     *
     * @param imp1
     * @param imp2
     * @return
     */
    private static double computePCC(ImagePlus imp1, ImagePlus imp2){
        // check image dimensions
        if(imp1.getWidth() != imp2.getWidth() || imp1.getHeight() != imp2.getHeight()) {
            IJLogger.error("computePCC","Image patches do not have the same dimensions ; w x h : "+imp1.getWidth()+" x " +imp1.getHeight() +" and " + imp2.getWidth()+" x "+imp2.getHeight());
            return Double.NaN;
        }

        List<Float> array1 = new ArrayList<>();
        List<Float> array2 = new ArrayList<>();

        // get raw pixel values
        for(int k = 0; k < imp1.getWidth(); k++){
            for (int l = 0; l < imp1.getHeight(); l++){
                array1.add(imp1.getProcessor().getPixelValue(k, l));
                array2.add(imp2.getProcessor().getPixelValue(k, l));
            }
        }

        // compute PCC
        PearsonsCorrelation pcc = new PearsonsCorrelation();
        return pcc.correlation(array1.stream().mapToDouble(Float::floatValue).toArray(), array2.stream().mapToDouble(Float::floatValue).toArray());
    }
}
