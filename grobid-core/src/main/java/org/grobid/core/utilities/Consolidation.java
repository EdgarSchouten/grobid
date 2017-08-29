package org.grobid.core.utilities;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;

import org.grobid.core.data.BiblioItem;
import org.grobid.core.exceptions.GrobidException;
import org.grobid.core.sax.CrossrefUnixrefSaxParser;
import org.grobid.core.utilities.crossref.*;
import org.grobid.core.utilities.crossref.CrossrefClient.RequestMode;
import org.grobid.core.utilities.counters.CntManager;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.xml.sax.InputSource;
import org.xml.sax.helpers.DefaultHandler;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;
import java.io.InputStream;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.List;
import java.util.StringTokenizer;

/**
 * Class for managing the extraction of bibliographical information from pdf documents.
 * When consolidation operations are realized, be sure to call the close() method
 * to ensure that all Executors are terminated.
 *
 * @author Patrice Lopez
 */
public class Consolidation {
    private static final Logger LOGGER = LoggerFactory.getLogger(Consolidation.class);

    private CrossrefClient client = null;
    private WorkDeserializer workDeserializer = null;
    private CntManager cntManager = null;

    public Consolidation(CntManager cntManager) {
        this.cntManager = cntManager;
        client = new CrossrefClient(RequestMode.MUCHTHENSTOP);
        workDeserializer = new WorkDeserializer();
    }

    /**
     * After consolidation operations, this need to be called to ensure that all
     * involved Executors are shut down immediatly, otherwise non terminated thread
     * could prevent the JVM from exiting
     */
    public void close() {
        client.close();
    }

    /**
     * Lookup by DOI - 3 parameters are id, password, doi.
     */
    private static final String DOI_BASE_QUERY =
            "openurl?url_ver=Z39.88-2004&pid=%s:%s&rft_id=info:doi/%s&noredirect=true&format=unixref";

    /**
     * Lookup by journal title, volume and first page - 6 parameters are id, password, journal title, author, volume, firstPage.
     */
    private static final String JOURNAL_AUTHOR_BASE_QUERY =
            //"query?usr=%s&pwd=%s&type=a&format=unixref&qdata=|%s||%s||%s|||KEY|";
            "servlet/query?usr=%s&pwd=%s&type=a&format=unixref&qdata=|%s|%s|%s||%s|||KEY|";

    // ISSN|TITLE/ABBREV|FIRST AUTHOR|VOLUME|ISSUE|START PAGE|YEAR|RESOURCE TYPE|KEY|DOI

    /**
     * Lookup by journal title, volume and first page - 6 parameters are id, password, journal title, volume, firstPage.
     */
    private static final String JOURNAL_BASE_QUERY =
            //"query?usr=%s&pwd=%s&type=a&format=unixref&qdata=|%s||%s||%s|||KEY|";
            "servlet/query?usr=%s&pwd=%s&type=a&format=unixref&qdata=|%s||%s||%s|||KEY|";

    /**
     * Lookup first author surname and  article title - 4 parameters are id, password, title, author.
     */
    private static final String TITLE_BASE_QUERY =
            "servlet/query?usr=%s&pwd=%s&type=a&format=unixref&qdata=%s|%s||key|";


    /**
     * Try to consolidate some uncertain bibliographical data with crossref web service based on
     * core metadata
     */
    public boolean consolidate(BiblioItem bib, List<BiblioItem> additionalBiblioInformation) throws Exception {
        boolean result = false;
        //List<BiblioItem> additionalBiblioInformation = new ArrayList<Biblio>();
        boolean valid = false;

        String doi = bib.getDOI();
        String aut = bib.getFirstAuthorSurname();
        String title = bib.getTitle();
        String journalTitle = bib.getJournal();
        String volume = bib.getVolume();
        if (StringUtils.isBlank(volume))
            volume = bib.getVolumeBlock();

        String firstPage = null;
        String pageRange = bib.getPageRange();
        int beginPage = bib.getBeginPage();
        if (beginPage != -1) {
            firstPage = "" + beginPage;
        } else if (pageRange != null) {
            StringTokenizer st = new StringTokenizer(pageRange, "--");
            if (st.countTokens() == 2) {
                firstPage = st.nextToken();
            } else if (st.countTokens() == 1)
                firstPage = pageRange;
        }

        if (aut != null) {
            aut = TextUtilities.removeAccents(aut);
        }
        if (title != null) {
            title = TextUtilities.removeAccents(title);
        }
        if (cntManager != null)
            cntManager.i(ConsolidationCounters.CONSOLIDATION);

        try {
            if (StringUtils.isNotBlank(doi)) {
                // retrieval per DOI
                //System.out.println("test retrieval per DOI");
                valid = consolidateCrossrefGetByDOI(bib, additionalBiblioInformation);
            }
            if (!valid && StringUtils.isNotBlank(title)
                    && StringUtils.isNotBlank(aut)) {
                // retrieval per first author and article title
                additionalBiblioInformation.clear();
                //System.out.println("test retrieval per title, author");
                valid = consolidateCrossrefGetByAuthorTitle(aut, title, bib, additionalBiblioInformation);
            }
            if (!valid && StringUtils.isNotBlank(journalTitle)
                    && StringUtils.isNotBlank(volume)
                    && StringUtils.isNotBlank(aut)
                    && StringUtils.isNotBlank(firstPage)) {
                // retrieval per journal title, author, volume, first page
                //System.out.println("test retrieval per journal title, author, volume, first page");
                additionalBiblioInformation.clear();
                valid = consolidateCrossrefGetByJournalVolumeFirstPage(aut, firstPage, journalTitle,
                        volume, bib, additionalBiblioInformation);
            }
            /*if (!valid && StringUtils.isNotBlank(journalTitle) 
                        && StringUtils.isNotBlank(volume)
                        && StringUtils.isNotBlank(firstPage)) {
                // retrieval per journal title, volume, first page
                additionalBiblioInformation.clear();
                //System.out.println("test retrieval per journal title, volume, first page");
                valid = consolidateCrossrefGetByJournalVolumeFirstPage(null, firstPage, journalTitle, 
                    volume, bib, additionalBiblioInformation);
            }*/
        } catch (Exception e) {
            throw new GrobidException("An exception occured while running Grobid consolidation.", e);
        }

        if (valid && (cntManager != null))
            cntManager.i(ConsolidationCounters.CONSOLIDATION_SUCCESS);
        return valid;
    }

    /**
     * Try to consolidate some uncertain bibliographical data with crossref web service based on
     * the DOI if it is around
     *
     * @param biblio the Biblio item to be consolidated
     * @param bib2   the list of biblio items found as consolidations
     * @return Returns a boolean indicating if at least one bibliographical object
     * has been retrieved.
     */
    public boolean consolidateCrossrefGetByDOI(BiblioItem biblio, List<BiblioItem> bib2) throws Exception {
        boolean result = false;
        String doi = biblio.getDOI();

        if (bib2 == null)
            return false;

        if (StringUtils.isNotBlank(doi)) {
            // some cleaning of the doi
            doi = doi.replace("\"", "");
            doi = doi.replace("\n", "");
            if (doi.startsWith("doi:") || doi.startsWith("DOI:") || 
                doi.startsWith("doi/") || doi.startsWith("DOI/") ) {
                doi.substring(4, doi.length());
                doi = doi.trim();
            }

            doi = doi.replace(" ", "");
            String xml = null;

            CrossrefRequestListener<BiblioItem> requestListener = new CrossrefRequestListener<BiblioItem>();
            client.<BiblioItem>pushRequest("works", doi, null, workDeserializer, requestListener);
            if (cntManager != null)
                cntManager.i(ConsolidationCounters.CONSOLIDATION_PER_DOI);

            synchronized (requestListener) {
                try {
                    requestListener.wait(5000); // timeout after 5 seconds
                } catch (InterruptedException e) {
                    LOGGER.error("Timeout error - " + ExceptionUtils.getStackTrace(e));
                }
            }
            
            CrossrefRequestListener.Response<BiblioItem> response = requestListener.getResponse();
            
            if (response == null)
                LOGGER.error("No response ! Maybe timeout.");
            
            else if (response.hasError() || !response.hasResults())
                LOGGER.error("error: ("+response.status+") : "+response.errorMessage);
            
            else { // success
                LOGGER.info("Success request "+ doi);
                if (cntManager != null)
                    cntManager.i(ConsolidationCounters.CONSOLIDATION_PER_DOI_SUCCESS);
                for (BiblioItem bib : response.results) {
                    bib2.add(bib);
                    result = true;
                }
            }
        }
        return result;
    }

    /**
     * Try to consolidate some uncertain bibliographical data with crossref web service based on
     * title and first author.
     *
     * @param biblio     the biblio item to be consolidated
     * @param biblioList the list of biblio items found as consolidations
     * @return Returns a boolean indicating whether at least one bibliographical object has been retrieved.
     */
    public boolean consolidateCrossrefGetByAuthorTitle(String aut, String title,
                                                       BiblioItem biblio, List<BiblioItem> bib2) throws Exception {

        boolean result = false;
        // conservative check
        if (StringUtils.isNotBlank(title) && StringUtils.isNotBlank(aut)) {
            String xml = null;
            // we check if the entry is not already in the DB
            /*if (cCon != null) {
                PreparedStatement pstmt = null;

                try {
                    pstmt = cCon.prepareStatement(QUERY_CROSSREF_SQL);
                    pstmt.setString(1, aut);
                    pstmt.setString(2, title);

                    ResultSet res = pstmt.executeQuery();
                    if (res.next()) {
                        xml = res.getString(1);
                    }
                } catch (SQLException se) {
//           			LOGGER.error("EXCEPTION HANDLING CROSSREF CACHE");
//           			se.printStackTrace();
                    throw new GrobidException("EXCEPTION HANDLING CROSSREF CACHE", se);
                } finally {
                    DbUtils.closeQuietly(pstmt);
                }

                if (xml != null) {
                    InputSource is = new InputSource();
                    is.setCharacterStream(new StringReader(xml));

                    DefaultHandler crossref = new CrossrefUnixrefSaxParser(bib2);

                    // get a factory
                    SAXParserFactory spf = SAXParserFactory.newInstance();
                    //get a new instance of parser
                    SAXParser parser = spf.newSAXParser();
                    parser.parse(is, crossref);

                    if (bib2.size() > 0) {
                        if (!bib2.get(0).getError())
                            result = true;
                    }
                }
            }*/

            if (xml == null) {
                String subpath = String.format(TITLE_BASE_QUERY,
                        GrobidProperties.getInstance().getCrossrefId(),
                        GrobidProperties.getInstance().getCrossrefPw(),
                        URLEncoder.encode(title, "UTF-8"),
                        URLEncoder.encode(aut, "UTF-8"));
                URL url = new URL("http://" + GrobidProperties.getInstance().getCrossrefHost() + "/" + subpath);

                LOGGER.info("Sending: " + url.toString());
                HttpURLConnection urlConn = null;
                try {
                    urlConn = (HttpURLConnection) url.openConnection();
                } catch (Exception e) {
                    try {
                        urlConn = (HttpURLConnection) url.openConnection();
                    } catch (Exception e2) {
                        urlConn = null;
                        throw new GrobidException("An exception occured while running Grobid.", e2);
                    }
                }
                if (urlConn != null) {
                    try {
                        urlConn.setDoOutput(true);
                        urlConn.setDoInput(true);
                        urlConn.setRequestMethod("GET");

                        urlConn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");

                        InputStream in = urlConn.getInputStream();
                        xml = TextUtilities.convertStreamToString(in);

                        InputSource is = new InputSource();
                        is.setCharacterStream(new StringReader(xml));

                        DefaultHandler crossref = new CrossrefUnixrefSaxParser(bib2);

                        // get a factory
                        SAXParserFactory spf = SAXParserFactory.newInstance();
                        //get a new instance of parser
                        SAXParser parser = spf.newSAXParser();
                        parser.parse(is, crossref);

                        if (bib2.size() > 0) {
                            if (!bib2.get(0).getError())
                                result = true;
                        }
                    } catch (Exception e) {
                        LOGGER.error("Warning: Consolidation set true, " +
                                "but the online connection to Crossref fails.");
                    } finally {
                        urlConn.disconnect();
                    }

                    /*if (cCon != null) {
                        // we put the answer (even in case of failure) un the DB
                        PreparedStatement pstmt2 = null;
                        try {
                            pstmt2 = cCon.prepareStatement(INSERT_CROSSREF_SQL);
                            pstmt2.setString(1, aut);
                            pstmt2.setString(2, title);
                            pstmt2.setString(3, xml);
                            pstmt2.executeUpdate();
                        } catch (SQLException se) {
                            LOGGER.error("EXCEPTION HANDLING CROSSREF UPDATE");
                        } finally {
                            DbUtils.closeQuietly(pstmt2);
                        }
                    }*/
                }
            }
        }
        return result;
    }

    /**
     * Try to consolidate some uncertain bibliographical data with crossref web service based on
     * the following core information: journal title, volume and first page.
     * We use also the first author if it is there, it can help...
     *
     * @param biblio     the biblio item to be consolidated
     * @param biblioList the list of biblio items found as consolidations
     * @return Returns a boolean indicating if at least one bibliographical object
     * has been retrieve.
     */
    public boolean consolidateCrossrefGetByJournalVolumeFirstPage(String aut,
                                                                  String firstPage,
                                                                  String journal,
                                                                  String volume,
                                                                  BiblioItem biblio,
                                                                  List<BiblioItem> bib2) throws Exception {

        boolean result = false;
        // conservative check
        if (StringUtils.isNotBlank(firstPage) &&
                StringUtils.isNotBlank(journal) && StringUtils.isNotBlank(volume)
                ) {
            String subpath = null;
            if (StringUtils.isNotBlank(aut))
                subpath = String.format(JOURNAL_AUTHOR_BASE_QUERY,
                        GrobidProperties.getInstance().getCrossrefId(),
                        GrobidProperties.getInstance().getCrossrefPw(),
                        URLEncoder.encode(journal, "UTF-8"),
                        URLEncoder.encode(aut, "UTF-8"),
                        URLEncoder.encode(volume, "UTF-8"),
                        firstPage);
            else
                subpath = String.format(JOURNAL_BASE_QUERY,
                        GrobidProperties.getInstance().getCrossrefId(),
                        GrobidProperties.getInstance().getCrossrefPw(),
                        URLEncoder.encode(journal, "UTF-8"),
                        URLEncoder.encode(volume, "UTF-8"),
                        firstPage);
            URL url = new URL("http://" + GrobidProperties.getInstance().getCrossrefHost() + "/" + subpath);
            String urlmsg = url.toString();
            System.out.println(urlmsg);

            String xml = null;

            /*if (cCon != null) {
                // we check if the query/entry is not already in the DB
                PreparedStatement pstmt = null;

                try {
                    pstmt = cCon.prepareStatement(QUERY_CROSSREF_SQL2);
                    pstmt.setString(1, urlmsg);

                    ResultSet res = pstmt.executeQuery();
                    if (res.next()) {
                        xml = res.getString(1);
                    }
                    res.close();
                    pstmt.close();
                } catch (SQLException se) {
                    throw new GrobidException("EXCEPTION HANDLING CROSSREF CACHE.", se);
                } finally {
                    try {
                        if (pstmt != null)
                            pstmt.close();
                    } catch (SQLException se) {
                    }
                }


                if (xml != null) {
                    InputSource is = new InputSource();
                    is.setCharacterStream(new StringReader(xml));

                    DefaultHandler crossref = new CrossrefUnixrefSaxParser(bib2);

                    // get a factory
                    SAXParserFactory spf = SAXParserFactory.newInstance();
                    //get a new instance of parser
                    SAXParser parser = spf.newSAXParser();
                    parser.parse(is, crossref);

                    if (bib2.size() > 0) {
                        if (!bib2.get(0).getError())
                            result = true;
                    }
                }
            }*/

            if (xml == null) {
                System.out.println("Sending: " + urlmsg);
                HttpURLConnection urlConn = null;
                try {
                    urlConn = (HttpURLConnection) url.openConnection();
                } catch (Exception e) {
                    try {
                        urlConn = (HttpURLConnection) url.openConnection();
                    } catch (Exception e2) {
                        urlConn = null;
                        throw new GrobidException("An exception occured while running Grobid.", e2);
                    }
                }
                if (urlConn != null) {

                    urlConn.setDoOutput(true);
                    urlConn.setDoInput(true);
                    urlConn.setRequestMethod("GET");

                    urlConn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                    InputStream in = null;
                    try {
                        in = urlConn.getInputStream();

                        xml = TextUtilities.convertStreamToString(in);

                        InputSource is = new InputSource();
                        is.setCharacterStream(new StringReader(xml));

                        DefaultHandler crossref = new CrossrefUnixrefSaxParser(bib2);

                        // get a factory
                        SAXParserFactory spf = SAXParserFactory.newInstance();
                        //get a new instance of parser
                        SAXParser p = spf.newSAXParser();
                        p.parse(is, crossref);
                        if (bib2.size() > 0 && !bib2.get(0).getError()) {
                            result = true;
                        }
                    } catch (Exception e) {
                        LOGGER.error("Warning: Consolidation set true, " +
                                "but the online connection to Crossref fails.");
                    } finally {
                        IOUtils.closeQuietly(in);
                        urlConn.disconnect();
                    }

                    /*if (cCon != null) {
                        // we put the answer (even in case of failure) un the DB
                        PreparedStatement pstmt2 = null;
                        try {
                            pstmt2 = cCon.prepareStatement(INSERT_CROSSREF_SQL2);
                            pstmt2.setString(1, urlmsg);
                            pstmt2.setString(2, xml);
                            pstmt2.executeUpdate();
                            pstmt2.close();
                        } catch (SQLException se) {
                            LOGGER.error("EXCEPTION HANDLING CROSSREF UPDATE");
                        } finally {
                            try {
                                if (pstmt2 != null)
                                    pstmt2.close();
                            } catch (SQLException se) {
                            }
                        }
                    }*/
                }
            }
        }
        return result;
    }

}
