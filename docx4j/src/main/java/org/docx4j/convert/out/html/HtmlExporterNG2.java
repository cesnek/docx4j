package org.docx4j.convert.out.html;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBElement;
import javax.xml.bind.Marshaller;
import javax.xml.bind.Unmarshaller;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.Source;
import javax.xml.transform.Templates;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.stream.StreamSource;

import org.apache.commons.vfs.CacheStrategy;
import org.apache.commons.vfs.FileObject;
import org.apache.commons.vfs.FileSystemException;
import org.apache.commons.vfs.FileSystemManager;
import org.apache.commons.vfs.impl.StandardFileSystemManager;
import org.apache.log4j.Logger;
import org.apache.xml.dtm.ref.DTMNodeProxy;
import org.docx4j.XmlUtils;
import org.docx4j.convert.out.Converter;
import org.docx4j.convert.out.Output;
import org.docx4j.convert.out.flatOpcXml.FlatOpcXmlCreator;
import org.docx4j.fonts.Mapper;
import org.docx4j.fonts.BestMatchingMapper;
import org.docx4j.fonts.IdentityPlusMapper;
import org.docx4j.fonts.PhysicalFont;
import org.docx4j.jaxb.Context;
import org.docx4j.model.PropertyResolver;
import org.docx4j.model.listnumbering.Emulator;
import org.docx4j.model.listnumbering.Emulator.ResultTriple;
import org.docx4j.model.properties.Property;
import org.docx4j.model.properties.PropertyFactory;
import org.docx4j.model.styles.StyleTree;
import org.docx4j.model.styles.StyleTree.AugmentedStyle;
import org.docx4j.model.styles.StyleTree.Tree;
import org.docx4j.openpackaging.exceptions.Docx4JException;
import org.docx4j.openpackaging.packages.WordprocessingMLPackage;
import org.docx4j.relationships.Relationship;
import org.docx4j.wml.PPr;
import org.docx4j.wml.RFonts;
import org.docx4j.wml.RPr;
import org.docx4j.wml.Style;
import org.docx4j.wml.Tbl;
import org.docx4j.wml.Tc;
import org.docx4j.wml.Tr;
import org.docx4j.wml.UnderlineEnumeration;
import org.docx4j.openpackaging.parts.Part;
import org.docx4j.openpackaging.parts.WordprocessingML.BinaryPart;

import org.w3c.dom.traversal.NodeIterator;
import org.w3c.dom.DOMException;
import org.w3c.dom.Document;
import org.w3c.dom.DocumentFragment;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.w3c.dom.Text;

/**
 * HtmlExporterNG has 2 salient features:
 * 
 * 1.  CSS .xx_pPr and .xx_rPr are created / split out for 
 *     each docx paragraph style
 *     
 * 2.  Each docx style is resolved into its effective
 *     style, by the PropertyResolver
 *     
 * When roundtripping HTML content, this has adverse
 * consequences (neither too bad, but adding to our work):
 * 
 * (a) It is harder to tell what has been added to the
 *     style definition in an editor (if/when that is 
 *     possible)
 *     
 * (b) Have to convert .xx_pPr and .xx_rPr back to xx
 * 
 * Also, we're not really taking advantage of the 
 * ability of CSS to cascade.
 * 
 * So this HtmlExporterNG2 takes a different approach.
 * It creates a css definition for each style in use
 * (either directly or indirectly), and in the
 * document, on the element's class attribute, puts the
 * set of style names in the hierarchy into . 
 *  
 * The CSS rules are written in a depth or breadth
 * first traversal of the style graph, so that  
 * conflicts are handled correctly.
 * 
 * TODO: handle document defaults.
 * 
 * 
 * @author jason
 *
 */
public class HtmlExporterNG2 extends HtmlExporterNG {
	
	
	protected static Logger log = Logger.getLogger(HtmlExporterNG2.class);
	
	public static void log(String message ) {
		
		log.info(message);
	}

	/**
	 * org/docx4j/convert/out/html/docx2xhtmlNG2.xslt will be used by default
	 * to transform the docx to html.
	 * This method allows you to use your own xslt instead.
	 * @param xslt
	 */		
	public static void setXslt(Templates xslt) {
		
		// This method is here, although it appears to duplicate the
		// method in the superclass, 
		// to force the class's static initializer to
		// run now, so it doesn't run later and overwrite
		// our explicit setting, which could be quite confusing.
		
		HtmlExporterNG.xslt = xslt;
	}	
	
	static {
		try {
			Source xsltSource = new StreamSource(
						org.docx4j.utils.ResourceUtils.getResource(
								"org/docx4j/convert/out/html/docx2xhtmlNG2.xslt"));
			xslt = XmlUtils.getTransformerTemplate(xsltSource);
		} catch (IOException e) {
			e.printStackTrace();
		} catch (TransformerConfigurationException e) {
			e.printStackTrace();
		}
	}
	
    
    /* ---------------Xalan XSLT Extension Functions ---------------- */
    
    public static String getCssForStyles(WordprocessingMLPackage wmlPackage) {
    	
    	StringBuffer result = new StringBuffer();
    	
    	StyleTree styleTree = wmlPackage.getMainDocumentPart().getStyleTree();
    	    	
		// First iteration - paragraph level pPr *and rPr*
		result.append("\n /* PARAGRAPH STYLES */ \n");    	
		Tree<AugmentedStyle> pTree = styleTree.getParagraphStylesTree();		
    	for (Tree<AugmentedStyle>.Node<AugmentedStyle> n : pTree.toList() ) {
    		Style s = n.getData().getStyle();

    		result.append( "."+ s.getStyleId()  + " {display:block;" );
        	if (s.getPPr()==null) {
        		log.debug("null pPr for style " + s.getStyleId());
        	} else {
        		createCss( s.getPPr(), result );
        	}
        	if (s.getRPr()==null) {
        		log.debug("null rPr for style " + s.getStyleId());
        	} else {
            	createCss(wmlPackage, s.getRPr(), result);
        	}
        	result.append( "}\n" );        	
    	}
		    	
	    // Second iteration, character styles
		result.append("\n /* CHARACTER STYLES */ ");
		//result.append("\n /* These come last, so they have more weight than the paragraph _rPr component styles */ \n ");
		
		Tree<AugmentedStyle> cTree = styleTree.getCharacterStylesTree();		
    	for (Tree<AugmentedStyle>.Node<AugmentedStyle> n : cTree.toList() ) {
    		Style s = n.getData().getStyle();

    		result.append( "."+ s.getStyleId()  + " {display:inline;" );
        	if (s.getRPr()==null) {
        		log.error("! null rPr for character style " + s.getStyleId());
        	} else {
            	createCss(wmlPackage, s.getRPr(), result);
        	}
        	result.append( "}\n" );        	
    	}		
	    return result.toString();
    }
    
    public static DocumentFragment createBlockForPPr( 
    		WordprocessingMLPackage wmlPackage,
    		NodeIterator pPrNodeIt,
    		String pStyleVal, NodeIterator childResults ) {
    	
    	StyleTree styleTree = wmlPackage.getMainDocumentPart().getStyleTree();
    	
    	// Note that this is invoked for every paragraph with a pPr node.
    	
    	// incoming objects are org.apache.xml.dtm.ref.DTMNodeIterator 
    	// which implements org.w3c.dom.traversal.NodeIterator

		if ( pStyleVal ==null || pStyleVal.equals("") ) {
			pStyleVal = "Normal";
		}
    	log.debug("style '" + pStyleVal );     		
    	
//    	log.info("pPrNode:" + pPrNodeIt.getClass().getName() ); // org.apache.xml.dtm.ref.DTMNodeIterator    	
//    	log.info("childResults:" + childResults.getClass().getName() ); 
    	
    	
        try {
        	
        	// Get the pPr node as a JAXB object,
        	// so we can read it using our standard
        	// methods.  Its a bit sad that we 
        	// can't just adorn our DOM tree with the
        	// original JAXB objects?
        	PPr pPr = null;
        	if (pPrNodeIt!=null) {
        		Node n = pPrNodeIt.nextNode();
        		if (n!=null) {
        			Unmarshaller u = Context.jc.createUnmarshaller();			
        			u.setEventHandler(new org.docx4j.jaxb.JaxbValidationEventHandler());
        			Object jaxb = u.unmarshal(n);
        			try {
        				pPr =  (PPr)jaxb;
        			} catch (ClassCastException e) {
        		    	log.error("Couldn't cast " + jaxb.getClass().getName() + " to PPr!");
        			}        	        			
        		}
        	}
        	
            // Create a DOM builder and parse the fragment			
        	DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();        
			Document document = factory.newDocumentBuilder().newDocument();
			
			//log.info("Document: " + document.getClass().getName() );

			Node xhtmlP = document.createElement("p");			
			document.appendChild(xhtmlP);
							
			if (log.isDebugEnabled() && pPr!=null) {					
				log.debug(XmlUtils.marshaltoString(pPr, true, true));					
			}				
		    
			// Set @class
			log.debug(pStyleVal);
			Tree<AugmentedStyle> pTree = styleTree.getParagraphStylesTree();		
	    	Tree<AugmentedStyle>.Node<AugmentedStyle> asn = pTree.get(pStyleVal);
			((Element)xhtmlP).setAttribute("class", 
					StyleTree.getHtmlClassAttributeValue(pTree, asn)			
			);
			
			
			// Does our pPr contain anything else?
			if (pPr!=null) {
				StringBuffer inlineStyle =  new StringBuffer();
				createCss(pPr, inlineStyle);				
				if (!inlineStyle.toString().equals("") ) {
					((Element)xhtmlP).setAttribute("style", inlineStyle.toString() );
				}
			}
			
			// Our fo:block wraps whatever result tree fragment
			// our style sheet produced when it applied-templates
			// to the child nodes
			// init
			DTMNodeProxy n = (DTMNodeProxy)childResults.nextNode();
			do {	
				
				// getNumberXmlNode creates a span node, which is empty
				// if there is no numbering.
				// Let's get rid of any such <span/>.
				
				// What we actually get is a document node
				if (n.getNodeType()==Node.DOCUMENT_NODE) {
					log.debug("handling DOCUMENT_NODE");
					// Do just enough of the handling here
	                NodeList nodes = n.getChildNodes();
	                if (nodes != null) {
	                    for (int i=0; i<nodes.getLength(); i++) {
	                    	
	        				if (((Node)nodes.item(i)).getLocalName().equals("span")
	        						&& ! ((Node)nodes.item(i)).hasChildNodes() ) {
	        					// ignore
	        					log.debug(".. ignoring <span/> ");
	        				} else {
	        					XmlUtils.treeCopy( (Node)nodes.item(i),  xhtmlP );	        					
	        				}
	                    }
	                }					
				} else {
					log.debug("handling DOCUMENT_NODE");
					
	//					log.info("Node we are importing: " + n.getClass().getName() );
	//					foBlockElement.appendChild(
	//							document.importNode(n, true) );
					/*
					 * Node we'd like to import is of type org.apache.xml.dtm.ref.DTMNodeProxy
					 * which causes
					 * org.w3c.dom.DOMException: NOT_SUPPORTED_ERR: The implementation does not support the requested type of object or operation.
					 * 
					 * See http://osdir.com/ml/text.xml.xerces-j.devel/2004-04/msg00066.html
					 * 
					 * So instead of importNode, use 
					 */
					XmlUtils.treeCopy( n,  xhtmlP );
				}
				// next 
				n = (DTMNodeProxy)childResults.nextNode();
				
			} while ( n !=null ); 
			
			DocumentFragment docfrag = document.createDocumentFragment();
			docfrag.appendChild(document.getDocumentElement());

			return docfrag;
						
		} catch (Exception e) {
			e.printStackTrace();
			System.out.println(e.toString() );
			log.error(e);
		} 
    	
    	return null;
    	
    }

    public static DocumentFragment createBlockForRPr( 
    		WordprocessingMLPackage wmlPackage,
    		String pStyleVal,
    		NodeIterator rPrNodeIt,
    		NodeIterator childResults ) {
    
    	StyleTree styleTree = wmlPackage.getMainDocumentPart().getStyleTree();
    	    	
    	// Note that this is invoked for every paragraph with a pPr node.
    	
    	// incoming objects are org.apache.xml.dtm.ref.DTMNodeIterator 
    	// which implements org.w3c.dom.traversal.NodeIterator

    	
//    	log.info("rPrNode:" + rPrNodeIt.getClass().getName() ); // org.apache.xml.dtm.ref.DTMNodeIterator    	
//    	log.info("childResults:" + childResults.getClass().getName() ); 
    	
    	
        try {
        	
        	// Get the pPr node as a JAXB object,
        	// so we can read it using our standard
        	// methods.  Its a bit sad that we 
        	// can't just adorn our DOM tree with the
        	// original JAXB objects?
			Unmarshaller u = Context.jc.createUnmarshaller();			
			u.setEventHandler(new org.docx4j.jaxb.JaxbValidationEventHandler());
			Object jaxb = u.unmarshal(rPrNodeIt.nextNode());
			
			RPr rPr = null;
			try {
				rPr =  (RPr)jaxb;
			} catch (ClassCastException e) {
		    	log.error("Couldn't cast " + jaxb.getClass().getName() + " to RPr!");
			}
        	
            // Create a DOM builder and parse the fragment
        	DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();        
			Document document = factory.newDocumentBuilder().newDocument();
			
			//log.info("Document: " + document.getClass().getName() );

			Node span = document.createElement("span");			
			document.appendChild(span);
			
			if (rPr==null) {
				Text err = document.createTextNode( "Couldn't cast " + jaxb.getClass().getName() + " to PPr!" );
				span.appendChild(err);
				
			} else {
				
				if (log.isDebugEnabled()) {					
					log.debug(XmlUtils.marshaltoString(rPr, true, true));					
				}
				
				if (pStyleVal==null || pStyleVal.equals("")) {
					pStyleVal = "Normal";
				}

				// Set @class	
				if ( rPr.getRStyle()!=null) {
					String rStyleVal = rPr.getRStyle().getVal();
					Tree<AugmentedStyle> cTree = styleTree.getCharacterStylesTree();		
			    	Tree<AugmentedStyle>.Node<AugmentedStyle> asn = cTree.get(rStyleVal);						
					((Element)span).setAttribute("class", 
							StyleTree.getHtmlClassAttributeValue(cTree, asn)			
					);				
				}
				
				// Does our rPr contain anything else?
				StringBuffer inlineStyle =  new StringBuffer();
				createCss(wmlPackage, rPr, inlineStyle);				
				if (!inlineStyle.toString().equals("") ) {
					((Element)span).setAttribute("style", inlineStyle.toString() );
				}
				
				
				// Our fo:block wraps whatever result tree fragment
				// our style sheet produced when it applied-templates
				// to the child nodes
				Node n = childResults.nextNode();
				XmlUtils.treeCopy( (DTMNodeProxy)n,  span );			
			}
			
			DocumentFragment docfrag = document.createDocumentFragment();
			docfrag.appendChild(document.getDocumentElement());

			return docfrag;
						
		} catch (Exception e) {
			e.printStackTrace();
			System.out.println(e.toString() );
			log.error(e);
		} 
    	
    	return null;
    	
    }
    
    

}