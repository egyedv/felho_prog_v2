
package functions;

import com.google.cloud.functions.BackgroundFunction;
import com.google.cloud.functions.Context;
import com.google.cloud.pubsub.v1.Publisher;
import com.google.cloud.translate.v3.DetectLanguageRequest;
import com.google.cloud.translate.v3.DetectLanguageResponse;
import com.google.cloud.translate.v3.LocationName;
import com.google.cloud.translate.v3.TranslationServiceClient;
import com.google.cloud.vision.v1.AnnotateImageRequest;
import com.google.cloud.vision.v1.AnnotateImageResponse;
import com.google.cloud.vision.v1.Feature;
import com.google.cloud.vision.v1.Image;
import com.google.cloud.vision.v1.ImageAnnotatorClient;
import com.google.cloud.vision.v1.ImageSource;
import com.google.protobuf.ByteString;
import com.google.pubsub.v1.ProjectTopicName;
import com.google.pubsub.v1.PubsubMessage;
import functions.eventpojos.GcsEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;


public class OcrProcessImage implements BackgroundFunction<GcsEvent> {
  private static final String PROJECT_ID = System.getenv("GCP_PROJECT");
  private static final String TRANSLATE_TOPIC_NAME = System.getenv("TRANSLATE_TOPIC");
  private static final String TO_LANGS = System.getenv("TO_LANG");

  private static final Logger logger = Logger.getLogger(OcrProcessImage.class.getName());
  private Publisher publisher;

  public OcrProcessImage() throws IOException {
    publisher = Publisher.newBuilder(
        ProjectTopicName.of(PROJECT_ID, TRANSLATE_TOPIC_NAME)).build();
  }

  @Override
  public void accept(GcsEvent gcsEvent, Context context) {
    String bucket = gcsEvent.getBucket();
    if (bucket == null) {
      throw new IllegalArgumentException("Missing bucket parameter");
    }
    String filename = gcsEvent.getName();
    if (filename == null) {
      throw new IllegalArgumentException("Missing name parameter");
    }

    detectText(bucket, filename);
  }

  private void detectText(String bucket, String filename) {
    logger.info("Looking for text in image " + filename);

    List<AnnotateImageRequest> visionRequests = new ArrayList<>();
    String gcsPath = String.format("gs://%s/%s", bucket, filename);

    ImageSource imgSource = ImageSource.newBuilder().setGcsImageUri(gcsPath).build();
    Image img = Image.newBuilder().setSource(imgSource).build();

    Feature textFeature = Feature.newBuilder().setType(Feature.Type.TEXT_DETECTION).build();
    AnnotateImageRequest visionRequest =
        AnnotateImageRequest.newBuilder().addFeatures(textFeature).setImage(img).build();
    visionRequests.add(visionRequest);

    // Detect text in an image using the Cloud Vision API
    AnnotateImageResponse visionResponse;
    try (ImageAnnotatorClient client = ImageAnnotatorClient.create()) {
      visionResponse = client.batchAnnotateImages(visionRequests).getResponses(0);
      if (visionResponse == null || !visionResponse.hasFullTextAnnotation()) {
        logger.info(String.format("Image %s contains no text", filename));
        return;
      }

      if (visionResponse.hasError()) {
        logger.log(
            Level.SEVERE, "Error in vision API call: " + visionResponse.getError().getMessage());
        return;
      }
    } catch (IOException e) {
      logger.log(Level.SEVERE, "Error detecting text: " + e.getMessage(), e);
      return;
    }

    String text = visionResponse.getFullTextAnnotation().getText();
    logger.info("Extracted text from image: " + text);

    logger.info("Sending translation request for language " + TO_LANGS);
    ApiMessage message = new ApiMessage(text, filename, TO_LANGS);
    ByteString byteStr = ByteString.copyFrom(message.toPubsubData());
    PubsubMessage pubsubApiMessage = PubsubMessage.newBuilder().setData(byteStr).build();
    try {
      publisher.publish(pubsubApiMessage).get();
    } catch (InterruptedException | ExecutionException e) {
      logger.log(Level.SEVERE, "Error publishing translation request: " + e.getMessage(), e);
      return;
    }

  }

}

