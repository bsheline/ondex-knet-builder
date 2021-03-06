package net.sourceforge.ondex.parser.medline2.xml;


import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipFile;

import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;

import org.apache.commons.lang3.StringUtils;
import org.codehaus.stax2.XMLInputFactory2;
import org.codehaus.stax2.XMLStreamReader2;

import com.ctc.wstx.stax.WstxInputFactory;

import net.sourceforge.ondex.parser.medline2.Parser;
import net.sourceforge.ondex.parser.medline2.sink.Abstract;
import net.sourceforge.ondex.tools.ziptools.ZipEndings;
import uk.ac.ebi.utils.regex.RegEx;
import uk.ac.ebi.utils.runcontrol.MultipleAttemptsExecutor;
import uk.ac.ebi.utils.xml.XmlFilterUtils;

/**
 * MEDLINE XML parser
 * 
 * @author keywan
 *
 */
public class XMLParser {

	private WstxInputFactory factory;

	private final static Pattern tabPattern = Pattern.compile("[\\t|\\n|\\r]");

	public XMLParser() {
		System.setProperty("javax.xml.stream.XMLInputFactory",
		"com.ctc.wstx.stax.WstxInputFactory");
		factory = (WstxInputFactory) WstxInputFactory.newInstance();
		factory.configureForLowMemUsage();
		factory.setProperty(XMLInputFactory2.IS_COALESCING, true);
		factory.setProperty(XMLInputFactory2.P_PRESERVE_LOCATION, false);
		factory.setProperty(XMLInputFactory2.P_REPORT_PROLOG_WHITESPACE, false);
		factory.setProperty(XMLInputFactory2.IS_VALIDATING, true);
		factory.setProperty(XMLInputFactory2.IS_SUPPORTING_EXTERNAL_ENTITIES,
				false);
		factory.setProperty(XMLInputFactory2.P_AUTO_CLOSE_INPUT, true);
		//		otherwise it will follow to link
		factory.setProperty(XMLInputFactory2.SUPPORT_DTD, false); 
	}


//	private final static String PUBMED_CIT = "PubmedArticle";

	private final static String MEDLINE_CIT = "MedlineCitation";

	private final static String PMID = "PMID";

	private final static String TITLE = "ArticleTitle";

	private final static String ABSTRACT = "Abstract";
	
	private final static String ABSTRACTTXT = "AbstractText";

//	private final static String AUTHORLIST = "AuthorList";

	private final static String AUTHOR = "Author";

//	private final static String JOURNAL = "Journal";

	private final static String JOURNAL_TITLE = "Title";

	private final static String CHEM = "Chemical";

	private final static String DATE = "ArticleDate";

//	private final static String REG_NUM = "RegistryNumber";

//	private final static String SUBS_NAME = "NameOfSubstance";

//	private final static String MESH = "MeshHeading";

	private final static String MESHDESCR = "DescriptorName";

//	private final static String ARTICLEID = "ArticleId";

	/** The XML content of these elements is wrapped with CDATA blocks, to avoid XML parser problems */
	private final static String [] CDATA_ELEMENTS = new String[] { "ArticleTitle", "AbstractText" };

	/** Matches stuff like {@code <MedlineDate>2000 Spring</MedlineDate>} in the hope to extrat the year */
	private final static RegEx MEDLINE_YEAR_RE = new RegEx ( "^([0-9]{4}).*$" );
	

	/**
	 * Reads one single PubMed/MEDLINE XML file.
	 * 
	 * @param file -
	 *            PubMed XML file to be parsed
	 * @return List of publications (Abstract)
	 */
	public Set<Abstract> parseMedlineXML(File file) throws IOException, XMLStreamException {

		//		System.out.println("Parsing file: " + file);
		Set<Abstract> abstracts = new HashSet<Abstract>();

		InputStream xmlin = null;

		// detecting whether input file is zipped or not
		int detectedEnding = ZipEndings.getPostfix(file);

		switch (detectedEnding) {

		case ZipEndings.GZ:
			xmlin = new GZIPInputStream ( new FileInputStream( file ) );
			break;
		case ZipEndings.ZIP:
			ZipFile zipFile = new ZipFile(file);
			if (zipFile.size() > 1) {
				System.err.println("There are multiple files in this zip file: can not parse");
			}
			xmlin = zipFile.getInputStream ( zipFile.entries().nextElement() );
			break;
		default:
			xmlin = new FileInputStream ( file );
		}

		// Wrap these elements with CDATA
		xmlin = XmlFilterUtils.cdataWrapper ( xmlin, CDATA_ELEMENTS );
		XMLStreamReader2 staxXmlReader = (XMLStreamReader2) factory.createXMLStreamReader( xmlin );

		abstracts = this.parse ( staxXmlReader );
		return abstracts;
	}

	/**
	 * Reads a set of PubMed IDs and uses web-services to retrieve the full entity
	 * 
	 * @param Set of PubMed IDs
	 * @return List of publications (Abstract)
	 */
	public Set<Abstract> parsePummedID(HashSet<String> ids) throws MalformedURLException, IOException, XMLStreamException 
	{
		// PubMED connections are hectic and need to be tried multiple times
    MultipleAttemptsExecutor attempter = new MultipleAttemptsExecutor ( 5, 500, 5000, IOException.class );

		final Set<Abstract> allabstracts = new HashSet<Abstract>();

		HttpURLConnection.setFollowRedirects(false);
		HttpURLConnection.setDefaultAllowUserInteraction(false);

		String accession = "";

		String id;

		int threshold = 100;
		int limit = 1;

		ArrayList<String> accsessions = new ArrayList<String>();

		Iterator<String> idsIt = ids.iterator();

		while (idsIt.hasNext()) {

			limit++;

			id = idsIt.next();
			accession = accession + id.trim() + ",";

			// create packages of 100 comma separeted PMIDs
			if (limit == threshold) {

				accession = accession.substring(0, accession.length() - 1);
				accsessions.add(accession);
				accession = "";
				limit = 1;
			}
		}
//		System.out.println("parsePummedID>> accessions: "+ accsessions);

		if (limit != 1) {

			accession = accession.substring(0, accession.length() - 1);
			accsessions.add(accession);
		}

		Iterator<String> it = accsessions.iterator();
		int i = 0;

		while (it.hasNext()) 
		{

			i++;
			accession = it.next();

			String efetchString = Parser.EFETCH_WS + accession;
//			System.out.println("\t For "+ efetchString +"...");

			URL efetch = new URL(efetchString);
			final Set<Abstract> abstracts = new HashSet<> ();
			
			// As said above, this needs to be tried multiple times.
			try {
				attempter.executeChecked ( () -> 
				{
					HttpURLConnection httpConn = (HttpURLConnection) efetch.openConnection();
					httpConn.setConnectTimeout( 10*60*1000 );
		
					// Wrap certain elements with CDATA			
					InputStream xmlin = XmlFilterUtils.cdataWrapper ( httpConn.getInputStream(), CDATA_ELEMENTS );
					XMLStreamReader2 staxXmlReader = (XMLStreamReader2) factory.createXMLStreamReader( xmlin );
					abstracts.clear ();
					abstracts.addAll ( this.parse ( staxXmlReader ) );
				});
			}
			catch ( Exception ex ) 
			{
				// IOEx happens only all attempts above have failed
				if ( ex instanceof IOException ) throw (IOException) ex;
				if ( ex instanceof XMLStreamException ) throw (XMLStreamException) ex;
				throw new Error ( 
					"Internal error: for some weird reason, we got an exception of unexpected type, message: " + ex.getMessage (),
					ex
				);
			}
				
			allabstracts.addAll(abstracts);
			
			if(i % 10 == 0){
				System.out.println("Retrieved "+ i * 100 + " out of "+ids.size()+" PMIDs.");
			}
		}

		return allabstracts;

	}

	/**
	 * Reads one single MEDLINE XML file. Parses for MedlineCitation and 
	 * return a set.
	 * 
	 * @param file -
	 *            MEDLINE XML file to be parsed
	 * @param importsession -
	 *            defining abstracts of interest
	 * @return Array of matching abstracts (Abstract)
	 */
	private Set<Abstract> parse(XMLStreamReader2 staxXmlReader) throws IOException, XMLStreamException {

		Set<Abstract> medlineCitationSet = new HashSet<Abstract>();
		
		while (staxXmlReader.hasNext()) {
			
			// skip DOCTYPE
			/*if(staxXmlReader.getEventType() == XMLStreamConstants.DTD) { System.out.println("skip DOCTYPE..."); staxXmlReader.nextTag(); }
			else {*/

			int event = staxXmlReader.next();
			String element;

			if(event == XMLStreamConstants.START_ELEMENT){
				element = staxXmlReader.getLocalName();

				// MedlineCitation starts
				if (element.equals(MEDLINE_CIT)) {
					Abstract medlineCit = parseMedlineCitation(staxXmlReader);
					medlineCitationSet.add(medlineCit);
					
				}
			}
		//}
		} // test else loop
		staxXmlReader.close();
		return medlineCitationSet;
	}
	
	

	public Abstract parseMedlineCitation(XMLStreamReader2 staxXmlReader) throws NumberFormatException, XMLStreamException{
		Abstract medlineCitation = new Abstract();

		boolean inMedlineCitationBlock = true;
		
		//jump to PMID element
		staxXmlReader.nextTag();
		
//		<!ELEMENT	PMID (#PCDATA)>	
		if (staxXmlReader.getLocalName().equals(PMID)) {
			int pmid = Integer.parseInt((staxXmlReader.getElementText()));
			medlineCitation.setID(pmid);
		}

		while (staxXmlReader.hasNext() && inMedlineCitationBlock) {

			int event = staxXmlReader.next();

			if(event == XMLStreamConstants.START_ELEMENT){
				String element = staxXmlReader.getLocalName();

//				<!ELEMENT	Title (#PCDATA)>
				if (element.equals(TITLE)) {
					medlineCitation.setTitle(staxXmlReader.getElementText());
				}
				
//				<!ELEMENT	Abstract (AbstractText+,CopyrightInformation?)>
//				<!ELEMENT	AbstractText (#PCDATA)>
//				<!ATTLIST       AbstractText
//				                Label CDATA #IMPLIED
//				                NlmCategory (UNLABELLED | BACKGROUND | OBJECTIVE | METHODS |
//				                             RESULTS | CONCLUSIONS) #IMPLIED>
				if (element.equals(ABSTRACT)) {
					String text = parseAbstract(staxXmlReader);
					medlineCitation.setBody(text);
				}

				// Journal/JournalIssue/PubDate 
				if (element.equals ( "Journal" ) )
				{
					String trackedElem = "Journal";
					
					while ( staxXmlReader.hasNext() )
					{
						event = staxXmlReader.next ();
						if ( event == XMLStreamConstants.END_ELEMENT && "Journal".equals ( element ) ) break;
						if ( event != XMLStreamConstants.START_ELEMENT ) continue;

						element = staxXmlReader.getLocalName ();						
												
						if ( "Journal".equals ( trackedElem ) && "JournalIssue".equals ( element ) )
							trackedElem = element;
						
						if ( "JournalIssue".equals ( trackedElem ) && "PubDate".equals ( element ) )
							trackedElem = element;
						
						if ( "PubDate".equals ( trackedElem ) )
						{
							Integer pubYear = null;

							// We can have either a Y/M/D date or a string element
							if ( "Year".equals ( element ) )
								pubYear = Integer.parseInt ( staxXmlReader.getElementText() );
							else if ( "MedlineDate".equals ( element ) ) 
							{
								pubYear = -1;
								
								// In case of string, we still hope to get the year from the string begin
								String dateStr = StringUtils.trimToNull ( staxXmlReader.getElementText () );
								if ( dateStr != null ) 
								{
									Matcher rem = MEDLINE_YEAR_RE.matcher ( dateStr );
									if ( rem.matches () )
										pubYear = Integer.parseInt ( rem.group ( 1 ) );
								}
							}
							if ( pubYear != null )
							{								
								// TODO: check for a sensible range, like >=1800 ?
								if ( pubYear >= 0 )	medlineCitation.setYear ( pubYear );

								// If non-null, we have met the relevant Year/Medline tags, so we can stop
								break;
							}
						} // if PubDate
					} // while on Journal
				
				} // if Journal
				

//				<!ELEMENT	Author (((LastName, ForeName?, Initials?, Suffix?) | 
//                        CollectiveName),NameID*)>
				if (element.equals(AUTHOR)){
					staxXmlReader.nextTag();
					String lastName = staxXmlReader.getElementText();
					staxXmlReader.nextTag();
					String foreName = "";
					if (staxXmlReader.getLocalName().equals("ForeName")){
						foreName = staxXmlReader.getElementText();
					}
					medlineCitation.addAuthors(foreName+" "+lastName);
				}
				
				
//				<!ELEMENT	Title (#PCDATA)>
				if(element.equals(JOURNAL_TITLE)){
					String journalTitle = staxXmlReader.getElementText();
					medlineCitation.setJournal(journalTitle);
				}


//				<!ELEMENT	Chemical (RegistryNumber,NameOfSubstance)>
				if (element.equals(CHEM)) {

					staxXmlReader.nextTag();
					String chemicalRegNum = staxXmlReader.getElementText();
					staxXmlReader.nextTag();
					String chemicalName = staxXmlReader.getElementText();
					
					if(chemicalRegNum.equals("0"))
						medlineCitation.addChemical(chemicalName);
					else
						medlineCitation.addChemical(chemicalRegNum+" "+chemicalName);
				}

//				<!ELEMENT	MeshHeading (DescriptorName, QualifierName*)>
				if (element.equals(MESHDESCR)) {
					String meshDescr = staxXmlReader.getElementText();
					medlineCitation.addMeSH(meshDescr);
				}


			}

			else if(event == XMLStreamConstants.END_ELEMENT 
					&& staxXmlReader.getLocalName().equals(MEDLINE_CIT)){
				inMedlineCitationBlock = false;
			}

		}

		return medlineCitation;
		
	}
	
	private String parseAbstract(XMLStreamReader2 staxXmlReader) throws XMLStreamException{
		String out = "";
		while(staxXmlReader.hasNext()){
            int event = staxXmlReader.next();
            if (event == XMLStreamConstants.START_ELEMENT
                    && staxXmlReader.getLocalName().equals(ABSTRACTTXT)) {
            	
				String text = staxXmlReader.getElementText().trim();
				text = tabPattern.matcher(text).replaceAll(" ");
				out += text+" ";
            	
            }else if (event == XMLStreamConstants.END_ELEMENT
                    && staxXmlReader.getLocalName().equals(ABSTRACT)) {
                break;
            }
			
		}
		
		return out;
	}
	
}
