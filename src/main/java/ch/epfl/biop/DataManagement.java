package ch.epfl.biop;

import fr.igred.omero.Client;
import fr.igred.omero.annotations.FileAnnotationWrapper;
import fr.igred.omero.annotations.MapAnnotationWrapper;
import fr.igred.omero.annotations.TableWrapper;
import fr.igred.omero.annotations.TagAnnotationWrapper;
import fr.igred.omero.exception.AccessException;
import fr.igred.omero.exception.OMEROServerError;
import fr.igred.omero.exception.ServiceException;
import fr.igred.omero.repository.DatasetWrapper;
import fr.igred.omero.repository.GenericRepositoryObjectWrapper;
import fr.igred.omero.repository.ImageWrapper;
import ij.IJ;
import ij.ImagePlus;
import ij.io.FileSaver;
import ij.macro.Variable;
import ij.measure.ResultsTable;
import omero.gateway.exception.DSAccessException;
import omero.gateway.exception.DSOutOfServiceException;
import omero.gateway.model.ImageData;
import omero.gateway.model.ObjectiveData;
import omero.gateway.model.TagAnnotationData;
import omero.model.NamedValue;

import java.io.*;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

public class DataManagement {

    static String argoSlideName = "Argo-SIM v2.0";

    /**
     * return the list of all images that have never been processed yet.
     *
     * @param client
     * @param datasetWrapper
     * @return
     * @throws AccessException
     * @throws ServiceException
     * @throws ExecutionException
     */
    public static List<ImageWrapper> filterNewImages(Client client, DatasetWrapper datasetWrapper) throws AccessException, ServiceException, ExecutionException {
        // get all images without the tags "raw" nor "process" and remove macro images from vsi files.
        return datasetWrapper.getImages(client).stream().filter(e-> {
            try {
                return (e.getTags(client).stream().noneMatch(t->(t.getName().equals("raw")||t.getName().equals("processed")))
                        && !(e.getName().contains("[macro image]")));
            } catch (ServiceException | AccessException | ExecutionException ex) {
                throw new RuntimeException(ex);
            }
        }).collect(Collectors.toList());
    }

    /**
     * return the list of all images (processed and unprocessed)
     *
     * @param client
     * @param datasetWrapper
     * @return
     * @throws AccessException
     * @throws ServiceException
     * @throws ExecutionException
     */
    public static List<ImageWrapper> filterAllImages(Client client, DatasetWrapper datasetWrapper) throws AccessException, ServiceException, ExecutionException {
        // get all images without the tags "raw" nor "process" and remove macro images from vsi files.
        return datasetWrapper.getImages(client).stream().filter(e-> {
            try {
                return (e.getTags(client).stream().noneMatch(t->t.getName().equals("processed"))
                        && !(e.getName().contains("[macro image]")));
            } catch (ServiceException | AccessException | ExecutionException ex) {
                throw new RuntimeException(ex);
            }
        }).collect(Collectors.toList());
    }


    /**
     * remove the extension from an image name
     *
     * @param name
     * @return
     */
    public static String getNameWithoutExtension(String name){
        // special case for lif files
        if(name.contains(".lif"))
            return name.replace(".lif","");
        // special case for vsi files
        if(name.contains(".vsi"))
            return name.replace(".vsi","");

        int pos = name.lastIndexOf(".");
        if (pos > 0) {
            name = name.substring(0, pos);
        }

        return name;
    }


    /**
     * add a list of tags to an image on OMERO
     * @param client
     * @param imageWrapper
     * @param tags
     */
    public static void addTags(Client client, ImageWrapper imageWrapper, List<String> tags){
        tags.forEach(tag->addTag(client,imageWrapper,tag));
    }


    /**
     * add a tag to an image on OMERO
     *
     * @param client
     * @param imageWrapper
     * @param tag
     */
    public static void addTag(Client client, ImageWrapper imageWrapper, String tag) {
        try {
            // get the corresponding tag in the list of available tags if exists
            List<TagAnnotationWrapper> rawTag = client.getTags().stream().filter(t -> t.getName().equals(tag)).collect(Collectors.toList());

            // check if the tag is already applied to the current image
            boolean isTagAlreadyExists = imageWrapper.getTags(client)
                    .stream()
                    .anyMatch(t -> t.getName().equals(tag));

            // add the tag to the current image if it is not already the case
            if (!isTagAlreadyExists) {
                imageWrapper.addTag(client, rawTag.isEmpty() ? new TagAnnotationWrapper(new TagAnnotationData(tag)) : rawTag.get(0));
                IJ.log("[INFO] [DataManagement][addTag] -- The tag " + tag + " has been successfully applied on the image " + imageWrapper.getId());
            } else IJ.log("[INFO] [DataManagement][addTag] -- The tag " + tag + " is already applied on the image " + imageWrapper.getId());

        }catch (ServiceException | OMEROServerError | AccessException | ExecutionException e){
            IJ.log("[ERROR] [DataManagement][addTag] -- The tag " + tag + " could not be applied on the image " + imageWrapper.getId());
        }
    }


    /**
     * add a list of key-values to an image on OMERO
     *
     * @param client
     * @param imageWrapper
     * @param keyValues
     */
    public static void addKeyValues(Client client, ImageWrapper imageWrapper, List<NamedValue> keyValues){
        // create a new MapAnnotation
        MapAnnotationWrapper newKeyValues = new MapAnnotationWrapper(keyValues);
        newKeyValues.setNameSpace("openmicroscopy.org/omero/client/mapAnnotation");
        try {
            // upload key-values on OMERO
            imageWrapper.addMapAnnotation(client, newKeyValues);
        }catch(ServiceException | AccessException | ExecutionException e){
            IJ.log("[ERROR] [DataManagement][addKeyValues] -- KeyValues could not be added on the image " + imageWrapper.getId());
        }
    }


    /**
     * get the acquisition date of the image by parsing the image name.
     * If the name is not correctly written, it finds the date in the image metadata.
     *
     * @param imageWrapper
     * @return
     */
    public static int getAcquisitionDate(ImageWrapper imageWrapper){
        // image name = MicroscopeName_objective_immersion_pattern_date.lif [imageNumber] for lif files
        // image name = MicroscopeName_objective_immersion_pattern_date_imageNumber for all other file formats

        String [] imgNameSplit = imageWrapper.getName().split("_");
        String imageFormat = imageWrapper.asImageData().asImage().getFormat().getValue().getValue();

        // check LIF files
        if(imageFormat.equals("LIF") && imgNameSplit.length == 5){
            String lastToken = imgNameSplit[4];
            String date = lastToken.split("\\.")[0];
            return Integer.parseInt(date.replace("d",""));
        }
        // for all other files
        else if(imgNameSplit.length == 6){
            String lastToken = imgNameSplit[4];
            return Integer.parseInt(lastToken.replace("d",""));
        }else{
            // if the file is not written correctly
            if(imageWrapper.asImageData().getAcquisitionDate() == null)
                return -1;

            String date = imageWrapper.asImageData().getAcquisitionDate().toLocalDateTime().toString().substring(0,10).replace("-","");
            return Integer.parseInt(date);
        }
    }

    /**
     * create all key-value pairs necessary for the project based on the dataset and the image name, as well as image metadata.
     *
     * @param client
     * @param imageWrapper
     * @param datasetName
     * @param microscope
     * @param processingParameters
     * @return
     */
    public static List<NamedValue> generateKeyValuesForProject(Client client, ImageWrapper imageWrapper, String datasetName, String microscope, Map<String,String> processingParameters){
        // WARNING: check for nulls beforehand (i, m, getModel(), ...)
        // image and dataset name have to be properly formatted
        // dataset name = CODE_Manufacturer_MicroscopeName
        // image name = MicroscopeName_objective_immersion_pattern_date.lif [imageNumber] for lif files
        // image name = MicroscopeName_objective_immersion_pattern_date_imageNumber for all other file formats

        // parse the image and dataset name
        String [] imgNameSplit = imageWrapper.getName().split("_");
        String [] datasetNameSplit = datasetName.split("_");
        boolean nameCorrectlyWritten = true;

        List<NamedValue> namedValues = new ArrayList<>();

        // check LIF files and correct image name
        if(!((imageWrapper.asImageData().asImage().getFormat().getValue().getValue().equals("LIF") && imgNameSplit.length == 5) || imgNameSplit.length == 6)){
            IJ.log("[WARN] [DataManagement][generateKeyValues] -- Image name not correctly written");
            nameCorrectlyWritten = false;
        }

        // get microscope name
        namedValues.add(new NamedValue("Microscope", microscope));

        if(datasetNameSplit.length > 2) {
            // get microscope manufacturer
            namedValues.add(new NamedValue("Manufacturer", datasetNameSplit[1]));
            // get microscope internal code
            namedValues.add(new NamedValue("Intern_Mic_Code", datasetNameSplit[0]));
        }
        if(nameCorrectlyWritten)
            // get the ArgoSlide pattern
            namedValues.add(new NamedValue("Argosim_pattern", imgNameSplit[3]));

        // get the ArgoSlide name
        namedValues.add(new NamedValue("Argoslide_name", argoSlideName));


        // get objective information (type, immersion, magnification and NA)
        try{
            ObjectiveData objective = client.getMetadata().getImageAcquisitionData(client.getCtx(), imageWrapper.getId()).getObjective();
            if(!objective.getModel().isEmpty())
                namedValues.add(new NamedValue("Objective", objective.getModel()));
            if(objective.getLensNA() != -1)
                namedValues.add(new NamedValue("NA", ""+objective.getLensNA()));
        }catch(NullPointerException | DSOutOfServiceException | ExecutionException | DSAccessException e){
            IJ.log("[WARN] [DataManagement][generateKeyValues] -- Not able to read objective metadata");
        }

        if(nameCorrectlyWritten) {
            namedValues.add(new NamedValue("Magnification", imgNameSplit[1]));
            namedValues.add(new NamedValue("Immersion", imgNameSplit[2]));
        }

        // add all processing parameters
        processingParameters.forEach((key, value) -> namedValues.add(new NamedValue(key, value)));

        return namedValues;
    }

    /**
     * Add to the current OMERO table some measurements or create a new OMERO table
     *
     * @param tableWrapper
     * @param client
     * @param rt
     * @param id
     * @return
     */
    public static TableWrapper populateDatasetTable(TableWrapper tableWrapper, Client client, ResultsTable rt, long id){

        try{
            if(tableWrapper == null)
                tableWrapper = new TableWrapper(client,rt,id,new ArrayList<>());
            else
                tableWrapper.addRows(client,rt,id,new ArrayList<>());

        }catch(ServiceException | AccessException | ExecutionException e){
            IJ.log("[ERROR] [DataManagement][populateDatasetTable] -- Could not populate an OMERO table ");
        }

        return tableWrapper;
    }


    /**
     * send measurements to OMERO. Add new measurements to the existing OMERO table if exists or create a new OMERO table
     *
     * @param client
     * @param datasetWrapper
     * @param newTable
     * @param title
     * @param folder
     * @param processAllImages
     */
    public static void sendDatasetTableToOmero(Client client, DatasetWrapper  datasetWrapper, TableWrapper newTable, String title, String folder, boolean processAllImages){
        // if previous tables, add results to the table
        if (newTable != null && !processAllImages){
            replaceTable(client, datasetWrapper, newTable.getId(), newTable, title);
            replaceTableCSVFile(client, datasetWrapper, newTable, title, folder);
        }else{
            //if no previous tables, create a new one and add it on OMERO
            newTable.setName(title);
            addTable(client, datasetWrapper, newTable);
            addTableAsCSV(client, datasetWrapper, newTable, folder);
        }
    }

    /**
     * create a new OMERO table with the imageJ ResultTable linked to a repository object.
     *
     * @param client
     * @param imageWrapper
     * @param rowId
     * @param rt
     * @param title
     */
    public static void sendImageTableToOmero(Client client, ImageWrapper imageWrapper, long rowId, ResultsTable rt, String title, String folder, boolean processAllImages){
        try{
            // get the tables that are already attached to the image/dataset
            TableWrapper tableWrapper = getLastOmeroTable(client, imageWrapper);

            // if previous tables, add results to the table
            if (tableWrapper != null && !processAllImages){
                tableWrapper.addRows(client, rt , rowId, new ArrayList<>(0));
                replaceTable(client, imageWrapper, tableWrapper.getId(), tableWrapper, title);
                replaceTableCSVFile(client, imageWrapper, tableWrapper, title, folder);
            }else{
                //if no previous tables, create a new one and add it on OMERO
                tableWrapper = new TableWrapper(client, rt , rowId, new ArrayList<>(0));
                tableWrapper.setName(title);
                addTable(client, imageWrapper, tableWrapper);
                addTableAsCSV(client, imageWrapper, tableWrapper, folder);
            }
        }catch(ServiceException | AccessException | ExecutionException e){
            IJ.log("[ERROR] [DataManagement][generateSummaryTable] -- Could not generate an OMERO table for " + imageWrapper.getName()+" ("+imageWrapper.getId()+")");
        }
    }


    /**
     * get the last table attached to an image/dataset/project...
     *
     * @param client
     * @param repoWrapper
     * @return
     */
    public static TableWrapper getLastOmeroTable(Client client, GenericRepositoryObjectWrapper<?>  repoWrapper) {
        try {
            // Prepare a Table
            List<TableWrapper> repoTables = repoWrapper.getTables(client);

            if (!repoTables.isEmpty()) {
                // get al names
                List<String> names = repoTables.stream().map(TableWrapper::getName).collect(Collectors.toList());

                // get dates
                List<String> orderedDate = new ArrayList<>();
                names.forEach(name-> orderedDate.add(name.split("_")[0]));

                // sort dates in reverse order (larger to smaller date)
                orderedDate.sort(Comparator.reverseOrder());

                // take the last table
                return repoTables.stream().filter(e->e.getName().contains(orderedDate.get(0))).collect(Collectors.toList()).get(0);
            } else
                return null;
        }catch(ServiceException | AccessException | ExecutionException e){
            IJ.log("[ERROR] [DataManagement][getTable] -- Could not get table attached to " + repoWrapper.getName()+" ("+repoWrapper.getId()+")");
            return null;
        }
    }


    /**
     * get the last table in the specified folder that correspond to the current microscope
     *
     * @param folder
     * @param testedMicroscope
     * @return
     */
    public static File getLastLocalTable(File folder, String testedMicroscope){
        // list all files within the folder
        File[] childfiles = folder.listFiles();

        if(childfiles == null)
            return null;

        // get all names of csv files
        List<String> names = Arrays.stream(childfiles)
                .filter(e -> e.isFile() && e.getName().contains("."))
                .filter(f -> f.getName().substring(f.getName().lastIndexOf(".") + 1).equals("csv"))
                .map(File::getName)
                .collect(Collectors.toList());

        if(names.isEmpty())
            return null;

        // filter only argoLight related csv files
        names = names.stream().filter(e->e.contains(testedMicroscope)).collect(Collectors.toList());

        // get dates
        List<String> orderedDate = new ArrayList<>();
        names.forEach(name-> orderedDate.add(name.split("_")[0]));

        // sort dates in reverse order (larger to smaller date)
        orderedDate.sort(Comparator.reverseOrder());

        if(orderedDate.isEmpty())
            return null;

        // get the name of the latest csv file
        String lastTable = names.stream().filter(e -> e.contains(orderedDate.get(0))).collect(Collectors.toList()).get(0);

        // return the csv file
        return new File(folder + File.separator + lastTable);
    }


    /**
     * convert a csv file into an ImageJ ResultsTable
     *
     * @param csvFile
     * @return
     */
    public static ResultsTable readCsvTableAsResultsTable(File csvFile){
        List<List<String>> data = new ArrayList<>();

        // read the csv file
        try (BufferedReader br = new BufferedReader(new FileReader(csvFile))) {
            String line;
            while ((line = br.readLine()) != null) {
                String[] values = line.split(",");
                data.add(Arrays.asList(values));
            }
        }catch (IOException e){
            IJ.log("[ERROR] [DataManagement][readCsvTableAsResultsTable] -- Error when reading csv file in "+csvFile.getAbsolutePath());
        }

        if(data.isEmpty())
            return null;

        // get headers
        List<String> header = data.get(0);
        data.remove(0);

        // built ResultsTable
        ResultsTable rt = new ResultsTable();
        // loop on columns
        for(int j = 0; j < data.size(); j++){
            List<String> row = data.get(j);
            rt.incrementCounter();
            // loop on rows
            for(int i = 0; i < row.size(); i++){
                rt.setValue(header.get(i), j, row.get(i));
            }
        }
        return rt;
    }

    /**
     * merge two ResultsTables into a single one
     *
     * @param rt1
     * @param rt2
     * @return
     */
    public static ResultsTable concatenateResultsTables(ResultsTable rt1, ResultsTable rt2){
        if(rt1 == null || rt2 == null)
            return null;

        int c1 = rt1.getLastColumn();
        int c2 = rt2.getLastColumn();

        // check the number of columns
        if(c1 != c2)
            return null;

        ResultsTable rt = new ResultsTable();
        String[] headings = rt1.getHeadings();

        for(int i = 0; i < c1; i++){
            // get the column of each table
            Variable[] col1 = rt1.getColumnAsVariables(headings[i]);
            Variable[] col2 = rt2.getColumnAsVariables(headings[i]);
            Variable[] col = new Variable[rt1.getCounter()+rt2.size()];
            // populate the new ResultsTable
            System.arraycopy(col1, 0, col, 0, rt1.size());
            System.arraycopy(col2, 0, col, rt1.size(), rt2.size());
            rt.setColumn(headings[i],col);
        }
        return rt;
    }

    /**
     * replace the table by a new one. tableWrapper has the id of the previous table to update but already contains new values.
     *
     * @param client
     * @param repoWrapper
     * @param tableWrapper
     * @param title
     */
    public static void replaceTable(Client client, GenericRepositoryObjectWrapper<?>  repoWrapper, long tableToDelete, TableWrapper tableWrapper, String title){
        try {
            // create the new table
            TableWrapper newTable = new TableWrapper(tableWrapper.createTable());
            newTable.setName(title);

            // add the new table
            repoWrapper.addTable(client, newTable);

            // delete the previous table
            client.deleteFile(tableToDelete);
            IJ.log("[INFO] [DataManagement][replaceTable] -- Table successfully updated for " + repoWrapper.getName()+" ("+repoWrapper.getId()+")");
        }catch(ServiceException | AccessException | OMEROServerError | ExecutionException | InterruptedException e){
            IJ.log("[ERROR] [DataManagement][replaceTable] -- Could not update table attached to " + repoWrapper.getName()+" ("+repoWrapper.getId()+")");
        }
    }


    /**
     * add a new OMERO table
     *
     * @param client
     * @param repoWrapper
     * @param tableWrapper
     */
    public static void addTable(Client client, GenericRepositoryObjectWrapper<?>  repoWrapper, TableWrapper tableWrapper){
        try {
            repoWrapper.addTable(client, tableWrapper);
            IJ.log("[INFO] [DataManagement][addTable] -- Table successfully added for " + repoWrapper.getName()+" ("+repoWrapper.getId()+")");
        }catch(ServiceException | AccessException | ExecutionException e){
            IJ.log("[ERROR] [DataManagement][addTable] -- Could not update table attached to " + repoWrapper.getName()+" ("+repoWrapper.getId()+")");
        }
    }

    /**
     * convert a list of images into one an image stack (list of n images ===> one image with n slices)
     *
     * @param imps
     * @return
     */
    private static ImagePlus convertImageListToImageStack(List<ImagePlus> imps){
        // get distinct image titles
        List<String> imagesTitle = imps.stream().map(ImagePlus::getTitle).distinct().collect(Collectors.toList());
        String name = "";

        // get the first image title
        if(imagesTitle.size() > 1)
            name = imagesTitle.get(0).split("_")[0];
        else name = imagesTitle.get(0);

        // create an image stack
        ImagePlus imp = IJ.createHyperStack(name,imps.get(0).getWidth(), imps.get(0).getHeight(),1,imps.size(),1,imps.get(0).getBitDepth());
                //IJ.createImage(name, ImageProcessing.heatMapBitDepth, imps.get(0).getWidth(), imps.get(0).getHeight(), imps.get(0).getBitDepth());

        for(int i = 0; i < imps.size(); i++){
            imp.setPosition(1,i+1,1);
            imp.setProcessor(imps.get(i).getProcessor());
        }

        return imp;
    }


    /**
     * save a list of images locally on the computer
     *
     * @param imps
     * @param imageName
     * @param folder
     */
    public static void saveHeatMapsLocally(List<ImagePlus> imps, String imageName, String folder){
        saveHeatMapLocally(convertImageListToImageStack(imps), imageName, folder);
    }


    /**
     * save an image locally on the computer and returns the saved file
     *
     * @param imp
     * @param imageName
     * @param folder
     * @return
     */
    public static File saveHeatMapLocally(ImagePlus imp, String imageName, String folder){
        FileSaver fs = new FileSaver(imp);
        // create an image file in the given folder, with the given imageName
        File analysisImage_output_path = new File(folder, imageName + "_" + imp.getTitle() + ".tif");

        // save the image
        boolean hasBeenSaved = fs.saveAsTiff(analysisImage_output_path.toString());

        // check if the image was correctly saved
        if(hasBeenSaved) IJ.log("[INFO] [DataManagement][saveHeatMapLocally] -- "+imp.getTitle()+".tif"+" was saved in : "+ folder);
        else IJ.log("[ERROR] [DataManagement][saveHeatMapLocally] -- Cannot save "+imp.getTitle()+ " in "+folder);

        return analysisImage_output_path;
    }


    /**
     * upload images on OMERO, in the given dataset.
     *
     * @param client
     * @param datasetId
     * @param imps
     * @param imageName
     * @param folder
     */
    public static void uploadHeatMaps(Client client, long datasetId, List<ImagePlus> imps, String imageName, String folder){
        uploadHeatMap(client, datasetId, convertImageListToImageStack(imps), imageName, folder);
    }


    /**
     * upload an image on OMERO, in the given dataset.
     *
     * @param client
     * @param datasetId
     * @param imp
     * @param imageName
     * @param folder
     */
    public static void uploadHeatMap(Client client, long datasetId, ImagePlus imp, String imageName, String folder){
        // save the image on the computer first and get the generate file
        File localImageFile = saveHeatMapLocally(imp, imageName, folder);
        try {
            // Import image on OMERO
            List<Long> analysisImage_omeroID = client.getDataset(datasetId).importImage(client, localImageFile.toString());
            ImageWrapper analysisImage_wpr = client.getImage(analysisImage_omeroID.get(0));
            IJ.log("[INFO] [DataManagement][uploadHeatMap] --"+imp.getTitle()+".tif"+" was uploaded to OMERO with ID : "+ analysisImage_omeroID);

            // add tags to the newly created image
            addTag(client, analysisImage_wpr, "processed");
            addTag(client, analysisImage_wpr, imp.getTitle().split(" ")[0]);
            addTag(client, analysisImage_wpr, "argolight");

        }catch (ServiceException | AccessException | ExecutionException | OMEROServerError e){
            IJ.log("[ERROR] [DataManagement][uploadHeatMap] -- Cannot upload heat maps on OMERO");
        } finally{
            // delete the file after upload
            boolean hasBeenDeleted = localImageFile.delete();
            if(hasBeenDeleted) IJ.log("[INFO] [DataManagement][uploadHeatMap] -- Temporary image deleted");
            else IJ.log("[ERROR] [DataManagement][uploadHeatMap] -- Cannot delete temporary saved image");
        }
    }


    /**
     * build a ResultTable from a TableWrapper
     *
     * @param tableWrapper
     * @return
     */
    private static ResultsTable getResultTableFromTableWrapper(TableWrapper tableWrapper){
        ResultsTable rt_image = new ResultsTable();

        // get results and table size from OMERO table
        Object[][] data = tableWrapper.getData();
        int nbCol = tableWrapper.getColumnCount();
        int nbRow = tableWrapper.getRowCount();

        // build the ResultsTable
        if(nbCol > 2){ // if we have more than a label to display

            for(int i = 0; i < nbRow; i++){
                rt_image.incrementCounter();
                // add the image IDs at the end of the table to be compatible with omero.parade
                ImageData imageData = (ImageData) (data[0][i]);
                rt_image.setValue(tableWrapper.getColumnName(0), i, imageData.getId());  // this is very important to get the image ids and be omero.parade compatible

                // populate the resultsTable
                for(int j = 1 ; j < nbCol; j++){
                    if(data[j][i] instanceof String)
                        rt_image.setValue(tableWrapper.getColumnName(j), i, (String)data[j][i]);
                    else  rt_image.setValue(tableWrapper.getColumnName(j), i, (double)data[j][i]);
                }
            }
        }

        return rt_image;
    }


    /**
     * replace an existing CSV file on OMERO by a new updated one
     *
     * @param client
     * @param repoWrapper
     * @param tableWrapper
     * @param title
     * @param folder
     */
    private static void replaceTableCSVFile(Client client, GenericRepositoryObjectWrapper<?>  repoWrapper, TableWrapper tableWrapper, String title, String folder){
        // generate an imageJ ResultsTable from an OMERO table
        ResultsTable rt = getResultTableFromTableWrapper(tableWrapper);

        // save locally the ResultsTable as a csv file.
        File localTableFile = new File(folder, title.replace(" ","_") + ".csv");
        rt.save(localTableFile.toString());

        try{
            // get csv files with a name matching the dataset name
            List<FileAnnotationWrapper> csvFiles = repoWrapper.getFileAnnotations(client)
                    .stream()
                    .filter(e -> e.getFileFormat().equals("csv") ||e.getFileFormat().equals("xls") && tableWrapper.getName().contains(e.getFileName().replaceAll("_Table.csv","")))
                    .collect(Collectors.toList());

            // delete those files
            for(FileAnnotationWrapper csvFile:csvFiles) {
                client.deleteFile(csvFile.getId());
                IJ.log("[INFO] [DataManagement][addTableAsCSV] -- File "+csvFile.getFileName()+" with id "+csvFile.getId()+" has been deleted");
            }

            // Import csv table on OMERO
            long fileID = repoWrapper.addFile(client, localTableFile);

            // test if all csv files are imported
            if(fileID != -1)
                IJ.log("[INFO] [DataManagement][addTableAsCSV] -- The imageJ Results table has been successfully imported on OMERO with id : "+fileID);
		    else
                IJ.log("[ERROR] [DataManagement][addTableAsCSV] -- The imageJ Results table has not been imported for some reasons");

        } catch (ServiceException | AccessException | ExecutionException | InterruptedException | OMEROServerError e){
            IJ.log("[ERROR] [DataManagement][addTableAsCSV] -- Cannot upload imageJ Results table on OMERO");
        } finally{
            // delete the file after upload
            boolean hasBeenDeleted = localTableFile.delete();
            if(hasBeenDeleted) IJ.log("[INFO] [DataManagement][addTableAsCSV] -- Temporary table deleted");
            else IJ.log("[ERROR] [DataManagement][addTableAsCSV] -- Cannot delete temporary saved table");
        }
    }

    /**
     * add a new ResultsTable as CSV file on OMERO
     *
     * @param client
     * @param repoWrapper
     * @param tableWrapper
     * @param folder
     */
    private static void addTableAsCSV(Client client, GenericRepositoryObjectWrapper<?>  repoWrapper, TableWrapper tableWrapper, String folder){
        // generate an imageJ ResultsTable from an OMERO table
        ResultsTable rt = getResultTableFromTableWrapper(tableWrapper);

        // save locally the ResultsTable as a csv file.
        File localTableFile = new File(folder, tableWrapper.getName().replace(" ","_") + ".csv");
        rt.save(localTableFile.toString());

        try{
            // Import csv table on OMERO
            long fileID = repoWrapper.addFile(client, localTableFile);

            // test if all csv files are imported
            if(fileID != -1)
                IJ.log("[INFO] [DataManagement][addTableAsCSV] -- The imageJ Results table has been successfully imported on OMERO with id : "+fileID);
            else
                IJ.log("[ERROR] [DataManagement][addTableAsCSV] -- The imageJ Results table has not been imported for some reasons");

        } catch (ExecutionException | InterruptedException e){
            IJ.log("[ERROR] [DataManagement][addTableAsCSV] -- Cannot upload imageJ Results table on OMERO");
        } finally{
            // delete the file after upload
            boolean hasBeenDeleted = localTableFile.delete();
            if(hasBeenDeleted) IJ.log("[INFO] [DataManagement][addTableAsCSV] -- Temporary table deleted");
            else IJ.log("[ERROR] [DataManagement][addTableAsCSV] -- Cannot delete temporary saved table");
        }
    }
}
