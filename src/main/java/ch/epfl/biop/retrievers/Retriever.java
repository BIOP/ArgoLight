package ch.epfl.biop.retrievers;

import ij.ImagePlus;

/**
 * Implements basic functionalities of an image retriever.
 */
public interface Retriever {
    /**
     * @param index image position in the list
     * @return the {@link ImagePlus} object of an image picked from the list of image to process.
     */
    ImagePlus getImage(int index);

    /**
     * @return the number of an images to process.
     */
    int getNImages();

    /**
     * @return the container id (local folder path or OMERO dataset id) that is used to save processing results
     */
    String getParentTarget();

    /**
     * @return true if you want to process all available images, regardless if they have already been processed
     */
    boolean isProcessingAllRawImages();
}
