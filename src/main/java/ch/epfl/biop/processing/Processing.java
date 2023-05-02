package ch.epfl.biop.processing;

import ch.epfl.biop.image.ImageChannel;
import ch.epfl.biop.image.ImageFile;
import ch.epfl.biop.retrievers.OMERORetriever;
import ch.epfl.biop.senders.LocalSender;
import ch.epfl.biop.senders.Sender;
import ch.epfl.biop.utils.IJLogger;
import fr.igred.omero.repository.ImageWrapper;
import ij.ImagePlus;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Intermediate class that process image and send results to the right place
 */
public class Processing {

    /**
     * Runs the processing on the current image and send results locally or to OMERO
     *
     * @param retriever retriever use to get data
     * @param savingHeatMaps true if you want to save heatmaps
     * @param sender sender object (local or OMERO sender)
     * @param userSigma user defined value of sigma for gaussian blurring
     * @param userMedianRadius user defined value of median radius for median filtering
     * @param userThresholdingMethod user defined thresholding method used
     * @param userParticleThreshold user defined value of the threshold on particle size
     * @param userRingRadius user defined value of the analysis circle radius around each ring
     */
    public static void run(OMERORetriever retriever, boolean savingHeatMaps, Sender sender, double userSigma, double userMedianRadius, String userThresholdingMethod,
                           double userParticleThreshold, double userRingRadius){
        Map<ImageWrapper, List<List<Double>>> summaryMap = new HashMap<>();
        List<String> headers = new ArrayList<>();

        for (int i = 0; i < retriever.getNImages(); i++) {
            try {
                // get the image
                ImagePlus imp = retriever.getImage(i);
                // get the imageWrapper
                ImageWrapper imageWrapper = retriever.getImageWrapper(i);
                if (imp == null || imageWrapper == null)
                    continue;

                IJLogger.info("Working on image "+imp.getTitle());
                // create a new ImageFile object
                ImageFile imageFile = new ImageFile(imp, imageWrapper.getId());
                boolean isOldProtocol = false;

                // choose the right ArgoLight processing
                if (!imageFile.getArgoSlideName().contains("ArgoSimOld")) {
                    ArgoSlideProcessing.run(imageFile, userSigma, userMedianRadius, userThresholdingMethod,
                            userParticleThreshold, userRingRadius);
                } else {
                    ArgoSlideOldProcessing.run(imageFile);
                    isOldProtocol = true;
                }

                IJLogger.info("End of processing");
                IJLogger.info("Sending results ... ");
                // send image results (metrics, rings, tags, key-values)
                sender.initialize(imageFile, imageWrapper);
                sender.sendTags(imageFile.getTags(), imageWrapper, retriever.getClient());
                sendResults(sender, imageFile, savingHeatMaps, isOldProtocol);

                // metrics summary to populate parent table
                Map<List<String>, List<List<Double>>> allChannelMetrics = imageFile.summaryForParentTable();
                headers = new ArrayList<>(allChannelMetrics.keySet()).get(0);
                if (!allChannelMetrics.values().isEmpty())
                    summaryMap.put(imageWrapper, allChannelMetrics.values().iterator().next());
            }catch (Exception e){
                IJLogger.error("An error occurred during processing ; cannot analyse the image "+retriever.getImage(i).getTitle());
            }
        }

        // populate parent table with summary results
        sender.populateParentTable(summaryMap, headers, !retriever.isProcessingAllRawImages());
    }

    /**
     * save processing results
     * @param sender
     * @param imageFile
     * @param savingHeatMaps
     * @param isOldProtocol
     */
    private static void sendResults(Sender sender, ImageFile imageFile, boolean savingHeatMaps, boolean isOldProtocol){
        Map<String, String> keyValues = imageFile.getKeyValues();

        // send PCC table
        if (imageFile.getNChannels() > 1)
            sender.sendPCCTable(imageFile.getPCC(), imageFile.getNChannels());

        List<List<Double>> distortionValues = new ArrayList<>();
        List<List<Double>> uniformityValues = new ArrayList<>();
        List<List<Double>> fwhmValues = new ArrayList<>();
        List<Integer> chIds = new ArrayList<>();

        for (int i = 0; i < imageFile.getNChannels(); i++) {
            ImageChannel channel = imageFile.getChannel(i);
            // get channel keyValues
            keyValues.putAll(channel.getKeyValues());
            // send Rois
            sender.sendGridPoints(channel.getGridRings(), channel.getId(), "measuredGrid");
            sender.sendGridPoints(channel.getIdealGridRings(), channel.getId(), "idealGrid");

            // collect metrics for each channel
            distortionValues.add(channel.getFieldDistortion());
            uniformityValues.add(channel.getFieldUniformity());
            fwhmValues.add(channel.getFWHM());
            chIds.add(channel.getId());

            // send heat maps
            if (savingHeatMaps) {
                if(!isOldProtocol && imageFile.getImagedFoV().equals("fullFoV")) sender.sendHeatMaps(channel.getFieldDistortionHeatMap(imageFile.getImgNameWithoutExtension()));
                if(!isOldProtocol && imageFile.getImagedFoV().equals("fullFoV")) sender.sendHeatMaps(channel.getFieldUniformityHeatMap(imageFile.getImgNameWithoutExtension()));
                if(!isOldProtocol && !imageFile.getImagedFoV().equals("fullFoV")) sender.sendHeatMaps(channel.getFWHMHeatMap(imageFile.getImgNameWithoutExtension()));
            }
        }

        // send results table
        if(!isOldProtocol && imageFile.getImagedFoV().equals("fullFoV")) sender.sendResultsTable(distortionValues, chIds, false, "Field_distortion");
        if(!isOldProtocol && imageFile.getImagedFoV().equals("fullFoV")) sender.sendResultsTable(uniformityValues, chIds, false, "Field_uniformity");
        if(!isOldProtocol && !imageFile.getImagedFoV().equals("fullFoV")) sender.sendResultsTable(fwhmValues, chIds, false, "FWHM");

        // send key values
        if(sender instanceof LocalSender)
            keyValues.put("Image_ID",""+imageFile.getId());
        sender. sendKeyValues(keyValues);
    }
}
