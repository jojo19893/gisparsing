package com.zeb.spark;

import com.google.common.collect.Sets;
import com.zeb.spark.MapNode;
import lombok.Getter;
import org.geotools.geometry.Envelope2D;
import org.geotools.geometry.GeometryBuilder;
import org.geotools.referencing.crs.DefaultGeographicCRS;

import javax.xml.namespace.QName;
import javax.xml.stream.XMLEventReader;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.events.StartElement;
import javax.xml.stream.events.XMLEvent;
import java.io.InputStream;
import java.io.StringReader;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAccessor;
import java.util.*;

/**
 * Created by jguenther on 12.12.2016.
 */
public class OSMParser implements Runnable {

    private final GeometryBuilder builder;
    private XMLEventReader reader;

    private String path;

    private HashMap<String, String> tags;


    private static final String MODIFY = "modify";
    private static final String DELETE = "delete";
    private static final String CREATE = "create";
    private static final String NODE = "node";

    Set<String> keys;

    @Getter
    private List<MapNode> malFormed = new ArrayList<>();

    @Getter
    private List<MapNode> updateNodes;
    @Getter
    private List<MapNode> deleteNodes;
    @Getter
    private List<MapNode> newNodes;

    private static ZoneId zone = ZoneId.systemDefault();


    public OSMParser(InputStream is, Collection<String> keys) {

        this.keys = Sets.newHashSet(keys);
        // Check
        tags = new HashMap<>();


        updateNodes = new ArrayList<>();
        deleteNodes = new ArrayList<>();
        newNodes = new ArrayList<>();
        this.builder = new GeometryBuilder(DefaultGeographicCRS.WGS84);
        try {
            reader = XMLInputFactory.newInstance().createXMLEventReader(is);
        } catch (XMLStreamException e) {
            e.printStackTrace();
        }
    }

    public OSMParser(String in, Collection<String> keys) {
        this.keys = Sets.newHashSet(keys);

        tags = new HashMap<>();

        updateNodes = new ArrayList<>();
        deleteNodes = new ArrayList<>();
        newNodes = new ArrayList<>();
        this.builder = new GeometryBuilder(DefaultGeographicCRS.WGS84);
        try {
            reader = XMLInputFactory.newInstance().createXMLEventReader(new StringReader(in));
        } catch (XMLStreamException e) {
            e.printStackTrace();
        }

    }

    public void clean() {
        updateNodes.clear();
        deleteNodes.clear();
        newNodes.clear();
        try {
            reader.close();
        } catch (XMLStreamException e) {
            e.printStackTrace();
        }
    }

    public void start() throws XMLStreamException {

        while (reader.hasNext()) {
            XMLEvent event = reader.nextEvent();
            if (event.isStartElement()) {
                StartElement start = event.asStartElement();
                if (start.getName().getLocalPart().matches("^(modify|create|delete)"))
                    handleCategory(start.getName().getLocalPart());

            }
            if (event.isEndElement()) {

            }

        }
        System.out.println("done");
    }

    private void handleCategory(String mode) throws XMLStreamException {
        while (reader.hasNext()) {
            XMLEvent event = reader.nextEvent();
            //R
            if (event.isEndElement() && event.asEndElement().getName().getLocalPart().matches("^(modify|delete|create)")) {
                return;
            }


            //Match all Nodes
            if (event.isStartElement() && event.asStartElement().getName().getLocalPart() == NODE) {
                //Map for Tags
                tags.clear();
                StartElement start = event.asStartElement();
                String nodeId = start.getAttributeByName(new QName("id")).getValue();
                String version = start.getAttributeByName(new QName("version")).getValue();
                String changeset = start.getAttributeByName(new QName("changeset")).getValue();
                String timeStamp = start.getAttributeByName(new QName("timestamp")).getValue();
                String lon = start.getAttributeByName(new QName("lon")).getValue();
                String lat = start.getAttributeByName(new QName("lat")).getValue();

                //Read Tag Elements
                while (reader.hasNext()) {
                    XMLEvent innerEL = reader.nextEvent();
                    if (innerEL.isEndElement() && innerEL.asEndElement().getName().getLocalPart() == NODE)
                        break;
                    //Tag Element
                    if (innerEL.isStartElement() && innerEL.asStartElement().getName().getLocalPart() == "tag") {
                        StartElement tag = innerEL.asStartElement();
                        String key = tag.getAttributeByName(new QName("k")).getValue();
                        String val = tag.getAttributeByName(new QName("v")).getValue();
                        tags.put(key, val);
                    }
                }
                // only append if banks or atms
                if ((tags.keySet().contains("amenity") && this.keys.contains(tags.get("amenity")))
                        // Take also shops
                        || tags.keySet().contains("shop")
                        ) {
                    MapNode ps = new MapNode();
                    // Nice casting^^
                    ps.setLon(Double.valueOf(lon));
                    ps.setLat(Double.valueOf(lat));
                    ps.setBounds(new Envelope2D(builder.createPoint(Double.valueOf(lon), Double.valueOf(lat)).getEnvelope()));
                    ps.setNodeId(Long.valueOf(nodeId));
                    ps.setVersion(Long.valueOf(version));
                    ps.setChangeSetId(Long.valueOf(changeset));
                    //Tag Attributes -> can be empty
                    String plz = tags.getOrDefault("addr:postcode", null);
                    if (plz != null) {
                        ps.setPlz((plz));
                    }

                    ps.setNodeType(tags.getOrDefault("amenity", tags.get("shop")));
                    ps.setTimeStamp(Instant.parse(timeStamp));
                    ps.setOpeningHours(tags.getOrDefault("opening_hours", null));
                    ps.setOperator(tags.getOrDefault("operator", null));
                    ps.setName(tags.getOrDefault("name:en", tags.getOrDefault("name", null)));
                    ps.setStreetName(tags.getOrDefault("addr:street", null));
                    ps.setCountry(tags.getOrDefault("addr:country", null));
                    ps.setCity(tags.getOrDefault("addr:city", null));
                    ps.setDataType(mode);
                    String wheel = tags.getOrDefault("wheelchair", Boolean.FALSE.toString());
                    ps.setWheelchair(wheel.equals("yes") ? true : false);

                    String dateAsString = DateTimeFormatter.ISO_LOCAL_DATE.withZone(zone).format(ps.getTimeStamp());
                    ps.setDateAsString(dateAsString);

                    switch (mode.toLowerCase()) {
                        case MODIFY:
                            updateNodes.add(ps);
                            break;
                        case CREATE:
                            newNodes.add(ps);
                            break;
                        case DELETE:
                            deleteNodes.add(ps);
                            break;
                        default:
                            malFormed.add(ps);
                            break;
                    }
                }
            }
        }

    }


    @Override
    public void run() {
        try {
            this.start();
        } catch (XMLStreamException e) {
            e.printStackTrace();
        }
    }
}
