package org.openedit.data;

import java.text.DateFormat;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.entermedia.locks.LockManager;
import org.openedit.Data;
import org.openedit.MultiValued;
import org.openedit.event.WebEvent;
import org.openedit.event.WebEventListener;
import org.openedit.profile.UserProfile;
import org.openedit.util.DateStorageUtil;

import com.openedit.ModuleManager;
import com.openedit.OpenEditException;
import com.openedit.WebPageRequest;
import com.openedit.config.Configuration;
import com.openedit.hittracker.HitTracker;
import com.openedit.hittracker.SearchQuery;
import com.openedit.hittracker.Term;
import com.openedit.users.User;
import com.openedit.util.URLUtilities;

public abstract class BaseSearcher implements Searcher, DataFactory
{
	private static final Log log = LogFactory.getLog(BaseSearcher.class);
	protected DateFormat fieldDefaultDateFormat;
	protected String fieldSearchType;
	protected String fieldCatalogId;
	protected static final String delim = ":";
	protected PropertyDetailsArchive fieldPropertyDetailsArchive;
	protected SearcherManager fieldSearcherManager;
	protected WebEventListener fieldWebEventListener;
	protected boolean fieldFireEvents = false;

	protected ModuleManager fieldModuleManager;
	public ModuleManager getModuleManager()
	{
		return fieldModuleManager;
	}

	public void setModuleManager(ModuleManager inModuleManager)
	{
		fieldModuleManager = inModuleManager;
	}
	protected String fieldNewDataName;
	
	public String getNewDataName()
	{
		if (fieldNewDataName == null)
		{
			fieldNewDataName = getPropertyDetails().getClassName();
		}
		return fieldNewDataName;
	}

	public void setNewDataName(String inDataClassName)
	{
		fieldNewDataName = inDataClassName;
	}

	public BaseSearcher()
	{
		super();
	}

	public QueryBuilder query()
	{
		QueryBuilder builder = new QueryBuilder();
		builder.setSearcher(this);
		return builder;
	}
	/*
	 * This is the main search method
	 */
	public HitTracker cachedSearch(WebPageRequest inPageRequest, SearchQuery inQuery) throws OpenEditException
	{
		if (inQuery == null){
			return null;
		}
		if (log.isDebugEnabled())
		{
			log.debug("checking: " + getCatalogId() + " " + inQuery.toFriendly());
		}
		addShowOnly(inPageRequest, inQuery);

		inPageRequest.putPageValue("searcher", this);
		HitTracker tracker = null;
		
		
		if (inQuery.isEmpty())
		{
			return null;
		}

		if (inQuery.getHitsName() == null)
		{
			String hitsname = inPageRequest.findValue("hitsname");
			if (hitsname == null)
			{
				hitsname = "hits";
			}
			inQuery.setHitsName(hitsname);
		}
		tracker = (HitTracker) inPageRequest.getSessionValue(inQuery.getSessionId());

		boolean runsearch = true;

		//String fullq = inQuery.toQuery();
			if (tracker != null)
			{
				if (!hasChanged(tracker))
				{
					if (inQuery.equals(tracker.getSearchQuery()))
					{
						runsearch = false;
						String cache = inPageRequest.getRequestParameter("cache");
						if (cache != null && !Boolean.parseBoolean(cache))
						{
							runsearch = true;
						}
					}
					if (inQuery.getSortBy() != null)
					{
						String oldSort = tracker.getOrdering();
						String currentsort = inQuery.getSortBy();
						if (!currentsort.equals(oldSort))
						{
							runsearch = true;
						}
					}
					if (inQuery.getSortBy() == null)
					{
						String oldSort = tracker.getOrdering();
						inQuery.setSortBy(oldSort);
					}
				}
			}
			if (runsearch)
			{
				try
				{
					UserProfile usersettings = (UserProfile) inPageRequest.getUserProfile();
					if (usersettings != null && inQuery.getSortBy() == null)
					{
						String sort = usersettings.getSortForSearchType(inQuery.getResultType());
						inQuery.setSortBy(sort);
					}
					if( inQuery.getSortBy() == null)
					{
						String sort = inPageRequest.findValue("sortby");
						inQuery.setSortBy(sort);
					}
					HitTracker oldtracker = tracker;
				//
					if(oldtracker != null && oldtracker.getSearchQuery().hasFilters() )
					{
						String clearfilters = inPageRequest.getRequestParameter("clearfilters");
						if( !Boolean.parseBoolean(clearfilters))
						{
							inQuery.setFilters(oldtracker.getSearchQuery().getFilters());
						}
					}
					
					tracker = search(inQuery); //search here ----
					tracker.setSearchQuery(inQuery);
				
					attachSecurityFilter(inPageRequest,tracker);
					
					
//					if(oldtracker != null && oldtracker.getSearchQuery().hasFilters() ){
//						tracker.setFilters(oldtracker.getFilters());			
//						tracker.refreshFilters();
//					}
					
					String hitsperpage = inPageRequest.getRequestParameter("hitsperpage");
					if (hitsperpage == null)
					{
						hitsperpage = inPageRequest.getPageProperty("hitsperpage");
					}
					if (hitsperpage == null)
					{
						
						if (usersettings != null)
						{
							tracker.setHitsPerPage(usersettings.getHitsPerPageForSearchType(inQuery.getResultType()));

						}
						else if (oldtracker != null)
						{
							tracker.setHitsPerPage(oldtracker.getHitsPerPage());
						}
					}
					else
					{
						tracker.setHitsPerPage(Integer.parseInt(hitsperpage));
					}

					if(oldtracker != null && oldtracker.hasSelections() )
					{
						tracker.loadPreviousSelections(oldtracker);
						tracker.setShowOnlySelected(oldtracker.isShowOnlySelected());
					}
					
					
					if (isFireEvents() && inQuery.isFireSearchEvent())
					{
						WebEvent event = new WebEvent();
						event.setSource(this);
						event.setOperation(getSearchType() + "/search");
						event.setSearchType(getSearchType());
						event.setUser(inPageRequest.getUser());
						event.setCatalogId(getCatalogId());
						event.addDetail("query", inQuery.toFriendly());
						event.addDetail("detailed", inQuery.toQuery());
						event.addDetail("hits", String.valueOf(tracker.getTotal()));
						event.addDetail("sort", inQuery.getSortBy());
						fireSearchEvent(event);
					}
				}
				catch (Throwable ex)
				{
					String fullq = inQuery.toQuery();
					inPageRequest.putPageValue("error", "Invalid search input. " + URLUtilities.xmlEscape(fullq));
					log.error(ex + " on " + fullq);
					ex.printStackTrace();
					inQuery.setProperty("error", "Invalid search " + URLUtilities.xmlEscape(fullq));
//					if( ex instanceof OpenEditException)
//					{
//						throw (OpenEditException)ex;
//					}
//					else
//					{
//						throw new OpenEditException(ex);
//					}	
				}
			}
		if (tracker != null)
		{
			if( !runsearch )
			{
				String hitsperpage = inPageRequest.getRequestParameter("hitsperpage");
				if( hitsperpage != null)
				{
					tracker.setHitsPerPage(Integer.parseInt(hitsperpage));
				}
			}
			String pagenumber = inPageRequest.getRequestParameter("page");
			if (pagenumber != null)
			{
				if ("next".equals(pagenumber))
				{
					int page = tracker.getPage();
					page++;
					tracker.setPage(page);
				}
				else if ("previous".equals(pagenumber))
				{
					int page = tracker.getPage();
					page--;
					tracker.setPage(page);
				}
				else
				{
					tracker.setPage(Integer.parseInt(pagenumber));
				}
			}
			if (tracker.getHitsName() == null)
			{
				String hitsname = inPageRequest.findValue("hitsname");
				if (hitsname != null)
				{
					tracker.setHitsName(hitsname);
				}
				else
				{
					tracker.setHitsName("hits");
				}
			}
				
			inPageRequest.putPageValue(tracker.getHitsName(), tracker);
			inPageRequest.putSessionValue(tracker.getSessionId(), tracker);
		}

		return tracker;
	}

	protected void attachSecurityFilter(WebPageRequest inPageRequest, HitTracker inTracker)
	{
		String enabled = inPageRequest.findValue("enforcesecurity");
		//log.info( "security filer enabled "  + enabled );
		if( Boolean.parseBoolean( enabled ) )
		{
			User user = inPageRequest.getUser();
			//log.info( "found filer user  "  + user + " " + user.isInGroup("administrators"));
			if(  user != null && user.isInGroup("administrators"))
			{
				//dont filter since its the admin
				return;
			}
			//log.info( "filer type " + getSearchType());
			
			//Run a search on another table, find a list of id's, add them to the query
			if( "library".equals( getSearchType() ) )
			{
				UserProfile profile = inPageRequest.getUserProfile();
				if( profile != null)
				{
					Collection<String> libraryids = profile.getCombinedLibraries();
					if( log.isDebugEnabled() )
					{
						log.debug( "added security filer for " + inPageRequest.getUserProfile() );
					}
					inTracker.getSearchQuery().setSecurityIds(libraryids);
				}
			}
		}
	}

	public boolean hasChanged(HitTracker inTracker)
	{
		String id1 = getIndexId();
		String id2 = inTracker.getIndexId();

		if (id1 == null || id1.equals(id2))
		{
			return false;
		}
		return true;
	}

	/**
	 * @deprecated Use loadHits which checks hitssessionid
	 * 
	 * @see org.openedit.data.Searcher#loadHits(com.openedit.WebPageRequest,
	 *      java.lang.String)
	 */
	public HitTracker loadHits(WebPageRequest inReq, String hitsname) throws OpenEditException
	{
		HitTracker otracker = (HitTracker) inReq.getSessionValue(hitsname + getCatalogId());
		
		if (otracker != null)
		{
			inReq.putPageValue(hitsname, otracker);
		}
		return otracker;
	}

	public HitTracker loadHits(WebPageRequest inReq)
	{
		String id = inReq.findValue("hitssessionid");
		if (id != null)
		{
			HitTracker tracker = (HitTracker) inReq.getSessionValue(id);
			tracker = checkCurrent(inReq, tracker);
			if (tracker != null)
			{
				String hitsname = inReq.findValue("hitsname");
				if( hitsname == null)
				{
					hitsname = tracker.getHitsName();
				}
				inReq.putPageValue(hitsname, tracker);
			}
			return tracker;
		}
		return null;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.openedit.data.Searcher#getDefaultDateFormat()
	 */
	public DateFormat getDefaultDateFormat()
	{
		if (fieldDefaultDateFormat == null)
		{
			fieldDefaultDateFormat = new SimpleDateFormat("yyyy-MM-dd");
		}
		return fieldDefaultDateFormat;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * org.openedit.data.Searcher#setDefaultDateFormat(java.text.DateFormat)
	 */
	public void setDefaultDateFormat(DateFormat inDefaultDateFormat)
	{
		fieldDefaultDateFormat = inDefaultDateFormat;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.openedit.data.Searcher#fieldSearch(com.openedit.WebPageRequest)
	 */
	public HitTracker fieldSearch(WebPageRequest inReq) throws OpenEditException
	{

		SearchQuery search = addStandardSearchTerms(inReq);

		if (search == null)
		{
			return null; // Noop
		}
		String sort = inReq.getRequestParameter("sortby");
		if (sort == null)
		{
			sort = inReq.findValue("sortby");
		}
		if (sort != null)
		{
			search.setSortBy(sort);
		}
		
		HitTracker hits = cachedSearch(inReq, search);
		return hits;
	}

	
	
	public HitTracker fieldSearch(String attr, String value, WebPageRequest inContext)
	{
		return fieldSearch(attr, value, null, inContext);
	}

	public HitTracker fieldSearch(String attr, String value, String orderby, WebPageRequest inContext)
	{
		SearchQuery query = createSearchQuery();

		if (attr != null && value != null)
		{
			PropertyDetail detail = new PropertyDetail();
			detail.setCatalogId(getCatalogId());
			detail.setId(attr);
			query.addExact(detail, value); //this is addMatches and not addExact so that we can handle wildcards
		}
		if (orderby != null)
		{
			query.setSortBy(orderby);
		}

		return cachedSearch(inContext, query);
	}
	
	
	public HitTracker fieldSearch(String attr, String value)
	{
		return fieldSearch(attr, value, (String)null);
	}

	public HitTracker fieldSearch(String attr, String value, String orderby)
	{
		SearchQuery query = createSearchQuery();

		if (attr != null && value != null)
		{
			PropertyDetail detail = new PropertyDetail();
			detail.setCatalogId(getCatalogId());
			detail.setId(attr);
			query.addExact(detail, value); //this is addMatches and not addExact so that we can handle wildcards
		}
		if (orderby != null)
		{
			query.setSortBy(orderby);
		}

		return search(query);
	}

	public HitTracker search(WebPageRequest inContext, String inQuery)
	{
		return search(inContext, parse(inQuery));
	}

	public SearchQuery parse(String inQuery)
	{
		String[] fields = inQuery.split(" ");
		SearchQuery query = createSearchQuery();
		for (int i = 0; i < fields.length; i++)
		{
			String[] parts = fields[i].split(":");
			if (parts.length != 2)
			{
				continue;
			}
			PropertyDetail detail = new PropertyDetail();
			detail.setCatalogId(getCatalogId());
			detail.setId(parts[0]);
			query.addMatches(detail, parts[1]);
		}
		return query;
	}

	public HitTracker search(WebPageRequest inPageRequest, SearchQuery search) throws OpenEditException
	{
		String defaultjoin = inPageRequest.getRequestParameter("defaultjoin");
		if (defaultjoin != null)
		{
			boolean andall = Boolean.parseBoolean(defaultjoin);
			search.setAndTogether(andall);
			search.setProperty("defaultjoin", defaultjoin);
		}
		String sort = inPageRequest.getRequestParameter("sortby");
		if (sort != null)
		{
			search.setSortBy(sort);
		}

		return cachedSearch(inPageRequest, search);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * org.openedit.data.Searcher#addStandardSearchTerms(com.openedit.WebPageRequest
	 * )
	 */
	public SearchQuery addStandardSearchTerms(WebPageRequest inPageRequest) throws OpenEditException
	{
		SearchQuery search = addFields(inPageRequest);
		search = addOrGroups(search, inPageRequest);
		
		if( search == null)
		{
			return null;
		}
		addShowOnly(inPageRequest, search);
		String resultype = inPageRequest.getRequestParameter("resulttype");
		if (resultype == null)
		{
			resultype = getSearchType();
		}
		search.setResultType(resultype);

		String fireevent = inPageRequest.findValue("fireevent");
		search.setFireSearchEvent(Boolean.parseBoolean(fireevent));
		return search;
	}
	
//	public void addFacets(WebPageRequest inReq, SearchQuery inSearch)
//	{
//
//		//Grab from userprofile.
//		UserProfile profile = inReq.getUserProfile();
//		List details = getPropertyDetails().getDetailsByProperty("filter", "true");
//		
//		//assettype, color, 
//		
//		for (Iterator iterator = details.iterator(); iterator.hasNext();)
//		{
//			PropertyDetail detail = (PropertyDetail) iterator.next();
//			HitTracker selected = profile.getFacetsForType(detail.getId());
//			
//			//Collection filters = inReq.getUserProfile().getValues(detail.getId() + "selectedfacets");
//			
//		}
//		
//
//		
//		
//		
//		// TODO Auto-generated method stub
//		
//	}

	protected SearchQuery addOrGroups(SearchQuery inSearch, WebPageRequest inPageRequest)
	{
		String[] orgroups = inPageRequest.getRequestParameters("orgroup");

		if (orgroups == null)
		{
			return inSearch;
		}
		if( inSearch == null)
		{
			inSearch = createSearchQuery();
		}


		for (int i = 0; i < orgroups.length; i++)
		{
			String[] vals = inPageRequest.getRequestParameters(orgroups[i] + ".value");
			if( vals != null)
			{
				StringBuffer buffer = new StringBuffer();
				for (int j = 0; j < vals.length; j++)
				{
					buffer.append(vals[j]);
					if( j < vals.length )
					{
						buffer.append(' ');
					}
				}
				inSearch.addOrsGroup(orgroups[i], buffer.toString());
			}
		}
		return inSearch;
	}

	protected SearchQuery addFields(WebPageRequest inPageRequest) throws OpenEditException
	{
		String[] fieldid = inPageRequest.getRequestParameters("field");

		if (fieldid == null )
		{
			return null;
		}
		SearchQuery search = createSearchQuery();
		String and = inPageRequest.getRequestParameter("querytype");
		if( and != null)
		{
			if("or".equals(and))
			{
				search.setAndTogether(false);
			}
		}
		
		String[] operations = inPageRequest.getRequestParameters("operation");
		// String[] values = inPageRequest.getRequestParameters("value");
		// check the new naming convention for values (fieldid.value)
		if (operations == null )
		{
			return null;
		}
		inPageRequest.removeSessionValue("crumb");


		DateFormat formater = null;
		String dateFormat = inPageRequest.getRequestParameter("dateformat");
		if (dateFormat != null)
		{
			formater = new SimpleDateFormat(dateFormat);
		}
		else
		{
			formater = getDefaultDateFormat(); // dateFormat = "MM/dd/yyyy";

		}

		Map valuecounter = new HashMap(fieldid.length);
		for (int i = 0; i < fieldid.length; i++)
		{
			//String id = termids[i];

			String field = fieldid[i];

			PropertyDetail detail = getDetail(field, inPageRequest);

			if (detail != null)
			{
				//the values are a counter. So each time we get a duplicate we inc the counter of values

				Integer count = (Integer) valuecounter.get(detail.getId());
				if (count == null)
				{
					count = new Integer(0);
				}
				else
				{
					count = new Integer(count.intValue() + 1);
				}
				valuecounter.put(detail.getId(), count);

				String[] vals = inPageRequest.getRequestParameters(detail.getId() + ".value");
				String val = null;
				if( vals != null && vals.length == 1 && vals[0].length() == 0)
				{
					vals = null;
				}
				
				if (vals != null && vals.length > count.intValue())
				{
					val = vals[count.intValue()]; //We should not get array out of bounds
				}
				if (val == null)
				{
					val = inPageRequest.getRequestParameter(field + ".value");
				}
				if( val != null && val.contains("|"))
				{
					vals = MultiValued.VALUEDELMITER.split(val);
					val = null;
				}
				else if (val == null)
				{
					vals = inPageRequest.getRequestParameters(detail.getId() + ".values");

//					//This is dumb
//					String[] ors = inPageRequest.getRequestParameters(detail.getId() + ".orvalue");
//					if (ors != null)
//					{
//						val = createOrValue(ors);
//					}
				}
				if( operations.length <= i)
				{
					log.info("Cant search without operations" );
					return null;
				}
				String op = operations[i];
				Term t = addTerm(search, detail, val, vals, op);
				if (t == null)
				{
					t = addDate(inPageRequest, search, formater, detail, val, op, count.intValue());
					if (t == null)
					{
//						t = addSelect(inPageRequest, search, detail, op);
//						if (t == null)
//						{
						t = addNumber(inPageRequest, search, detail, val, op);
						if (t == null)
						{
							//This is for lobpicker and primaryproductpicker and maybe other ones
							addPicker(inPageRequest, search, detail, val, op, count.intValue());
						}
//						}
					}
				}

			}
		}
		return search;
	}

	public void addShowOnly(WebPageRequest inPageRequest, SearchQuery search)
	{
		if(inPageRequest == null){
			return;
		}
		String querystring = inPageRequest.findValue(getSearchType() + "showonly");
		if( querystring == null )
		{
			//Legacy check. Remove this line after Feb 15 2013
			querystring = inPageRequest.findValue("showonly");
		}
		if (querystring != null)
		{
			addShowOnlyFilter(inPageRequest, querystring, search);
		}
	}
	
	public void addShowOnlyFilter(WebPageRequest inPageRequest, String querystring, SearchQuery search){
		
		if (querystring != null)
		{
			SearchQuery child = search.getChildQuery(querystring);
			if( child == null)
			{
				child = createSearchQuery(querystring, inPageRequest);
				child.setFilter(true);
				child.setId(querystring); //unique
				//make sure we dont have a conflict of fields? i.e. already searching by a certain term
				for (int i = 0; i < search.getTerms().size(); i++)
				{
					Term existingterm = (Term)search.getTerms().get(i);
					int c = 0;
					while( true )
					{
						Term childterm = child.getTermByDetailId(existingterm.getDetail().getId());
						if( childterm != null)
						{
							child.removeTerm(childterm);
						}
						else
						{
							break;
						}
						c++;
						if( c > 100)
						{
							log.error("infinite loop should never happen");
							break;
						}
					}
				}
				if( child.getTerms().size() > 0 || child.getChildren().size() > 0)
				{
					search.addChildQuery(child);
				}
			}
		}
	}

	public void addUserProfileSearchFilters(WebPageRequest inReq, SearchQuery search) 
	{
		if( inReq.getUserProfile() == null)
		{
			return;
		}
		Collection filters = inReq.getUserProfile().getValues("profilesearchfilters");
		//hideassettype
		//String profileid = inReq.findValue("profilevalues");
		//String field = inReq.findValue("field");
		if (filters==null) 
		{
			return;
		}
		for (Iterator iter = filters.iterator(); iter.hasNext();) 
		{
			String filter = (String) iter.next();
			Collection values = inReq.getUserProfile().getValues(filter);
			if( values != null)
			{
				String [] terms = new String[values.size()];
				Iterator iterator = values.iterator();
				String term = null;
				String field = filter.substring(4);
				SearchQuery child = search.getChildQuery(filter);
				if( child != null)
				{
					search.getChildren().remove(child);
				}
				
				if( filter.startsWith("hide"))
				{
					term = "-" + field;
				}
				else if( filter.startsWith("show"))
				{
					term = "+" + field;
				}
				
				for (int i = 0; i < terms.length; i++)
				{
					terms[i]=term + ":" + iterator.next();
				} 
				
				SearchQuery newchild = createSearchQuery(terms, inReq);
				newchild.setId(filter);
				newchild.setFilter(true);
				search.addChildQuery(newchild);
			} else{
				SearchQuery child = search.getChildQuery(filter);
				if( child != null)
				{
					search.getChildren().remove(child);
				}
			}
		}		
	}
	
	protected PropertyDetail getDetail(String inString, WebPageRequest inReq)
	{
		String searchtype = inReq.findValue("searchtype");
		if (searchtype == null)
		{
			searchtype = getSearchType();
		}

		PropertyDetail detail = null;
		String catalogid = null;
		String view = null;
		String[] splits = inString.split(BaseSearcher.delim);

		String propertyid = inString; //this is for backward compatability
		if (splits.length > 2)
		{
			catalogid = splits[0];
			view = splits[1];
			propertyid = splits[2];
		}

		if (propertyid == null || propertyid.length() == 0)
		{
			return null;
		}

		if (catalogid == null)
		{
			catalogid = getCatalogId();
		}
//		Searcher remoteSearcher = getSearcherManager().getSearcher(catalogid, searchtype);
//		if (remoteSearcher != null)
//		{
//			detail = remoteSearcher.getDetailForView(view, propertyid, inReq.getUser());
//		}
//		if (detail == null)
//		{
		detail = getPropertyDetailsArchive().getDetail(getSearchType(), view, propertyid, inReq.getUserProfile());  //Add View.getDetail( API then make sure everyone get's a cached view object
//		}
//		if (detail == null)
//		{
//			detail = getDetail(propertyid);
//		}
		if (detail == null)
		{
			// continue;
			// create a virtual one?
			detail = new PropertyDetail();
			detail.setId(propertyid);
			detail.setView(view);
			detail.setCatalogId(catalogid);
		}
		detail.setSearchType(searchtype);

		return detail;
	}
	protected Term addTerm(SearchQuery search, PropertyDetail detail, String val, String op)
	{
		return addTerm(search, detail, val, null, op);
	}

	protected Term addTerm(SearchQuery search, PropertyDetail detail, String val, String[] vals, String op)
	{
		Term t = null;
		if(detail.isDataType("number") || detail.isDataType("double") || detail.isDataType("float")){
			return null;
		}
		if ((val != null && val.length() > 0))
		{
			if ("matches".equals(op) || "andgroup".equals(op))
			{
				t = search.addMatches(detail, val);
			}
			else if ("exact".equals(op))
			{
				t = search.addExact(detail, val);
			}
			else if ("startswith".equals(op))
			{
				t = search.addStartsWith(detail, val);
			}
			else if ("contains".equals(op))
			{
				t = search.addContains(detail, val);
			}
			else if ("not".equals(op))
			{
				t = search.addNot(detail, val);
			}
			else if ("orsgroup".equals(op))
			{
				t = search.addOrsGroup(detail, val);
			}
			else if ("freeform".equals(op))
			{
				t = search.addFreeFormQuery(detail, val);
			}
			
			if( t != null )
			{
				search.setProperty(t.getId(), val);
			}
		}
		else if( vals != null )
		{
			if ("andgroup".equals(op))
			{
				t = search.addAndGroup(detail, vals);
			}
			else
			{
				t = search.addOrsGroup(detail, vals);
			}
			search.setPropertyValues(t.getId(), vals);
		}
		if (t != null)
		{
			t.addParameter("op", op);
		}
		return t;
	}

	private String createOrValue(String[] ors)
	{
		String val = null;
		if (ors.length > 1)
		{
			StringBuffer orString = new StringBuffer();
			orString.append("(");
			for (int j = 0; j < ors.length; j++)
			{
				orString.append(ors[j]);
				if (j < ors.length - 1)
				{
					orString.append(" OR ");
				}
			}
			orString.append(")");
			val = orString.toString();
		}
		else if (ors.length == 1)
		{
			val = ors[0];
		}
		return val;
	}

	protected Term addPicker(WebPageRequest inReq, SearchQuery inQuery, PropertyDetail inDetail, String inVal, String inOp, int inIndex)
	{
		Term t = null;
		if ("is".equals(inOp))
		{
			t = inQuery.addMatches(inDetail, inVal);
			inQuery.setProperty(t.getId(), inVal);
			String tmp = inReq.getRequestParameters(inDetail.getId() + ".additionals")[inIndex];
			String[] additionals = tmp.split(",");
			for (int i = 0; i < additionals.length; i++)
			{
				String paramid = inDetail.getId() + "." + additionals[i];
				String inputid = t.getId() + "." + additionals[i];
				String val = inReq.getRequestParameters(paramid)[inIndex];
				inQuery.setProperty(inputid, val);
			}
		}
		return t;
	}

	protected Term addNumber(WebPageRequest inPageRequest, SearchQuery search, PropertyDetail field, String val, String op)
	{
		if(!(field.isDataType("number") || field.isDataType("double") || field.isDataType("float"))){
			return null;
		}
		
		Term t = null;
		if (val != null && val.length() > 0)
		{
		
			
			if ("greaterthannumber".equals(op))
			{
				t = search.addGreaterThan(field, Long.parseLong(val));
			}
			else if ("lessthannumber".equals(op))
			{
				t = search.addLessThan(field, Long.parseLong(val));
			}
			
			if ("equaltonumber".equals(op) || "matches".equals(op) || "startswith".equals(op))
			{
				if(field.isDataType("number") || field.isDataType("long")){
					t = search.addExact(field, Long.parseLong(val));
				} 
				if(field.isDataType("double")){
					t = search.addExact(field, Double.parseDouble(val));
				}
			}
			else if("betweennumbers".equals(op))
			{
				String highval = null;
				String lowval = null; 
				//see if we have a range
				if( !val.contains("-"))
				{
					t = search.addExact(field, Long.parseLong(val));
				}
				else
				{
					String[] range = val.split("-");
					if(range[0].length()> 0 )
					{
						lowval = range[0];
					}
					if( range.length > 1 && range[1].length() > 0)
					{
						highval = range[1]; 	
					}
					t = addNumberRange(search, field, t, highval, lowval);
				}
			}
			if (t != null)
			{
				search.setProperty(t.getId(), val);
				t.addParameter("op", op);
			}
		}
		else if ("betweennumbers".equals(op))
		{
			String highval = inPageRequest.getRequestParameter(field + ".highval");
			String lowval = inPageRequest.getRequestParameter(field + ".lowval");
			t = addNumberRange(search, field, t, highval, lowval);
		}

		return t;
	}

	protected Term addNumberRange(SearchQuery search, PropertyDetail field, Term t, String highval, String lowval)
	{
		if (highval != null && lowval == null)
		{
			t = search.addLessThan(field, Long.parseLong(highval));
		}
		else if (highval == null && lowval != null)
		{
			t = search.addGreaterThan(field, Long.parseLong(lowval));
		}
		else if (highval != null && lowval != null)
		{
			t = search.addBetween(field, Long.parseLong(lowval), Long.parseLong(highval));
		}
		return t;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.openedit.data.Searcher#deselect(java.lang.String,
	 * java.lang.String[])
	 */
	public List deselect(String inField, String[] toremove) throws OpenEditException
	{
		HitTracker options = getSearcherManager().getList(getPropertyDetailsArchive().getCatalogId(), inField);
		List list = options.keys();
		if (toremove != null)
		{
			for (int i = 0; i < toremove.length; i++)
			{
				String name = toremove[i];
				list.remove(name);
			}
		}
		return list;
	}

	private Term addSelect(WebPageRequest inPageRequest, SearchQuery search, PropertyDetail field, String op) throws OpenEditException
	{
		Term t = null;
		if ("multiselect".equals(op))
		{
			String param = field + ".value";

			String[] select = inPageRequest.getRequestParameters(param);
			if (select != null)
			{
				List remaining = deselect(field.getId(), select);
				for (int j = 0; j < remaining.size(); j++)
				{
					String val = (String) remaining.get(j);
					t = search.addNot(field, val);
					search.setProperty(t.getId(), val);
				}
				// search.addCategoryFilter(remaining, friendly);
			}
		}
		else if ("picker".equals(op))
		{
			String param = field + ".value";

			String[] select = inPageRequest.getRequestParameters(param);
			if (select != null)
			{
				SearchQuery or = createSearchQuery();
				or.setAndTogether(false);
				for (int j = 0; j < select.length; j++)
				{
					t = or.addMatches(field, select[j]);
					search.setProperty(t.getId(), select[j]);
				}
				search.addQuery(field, or.toQuery());
				search.setPropertyValues("picker." + param, select);
			}
		}
		return t;
	}

	protected Term addDate(WebPageRequest inPageRequest, SearchQuery search, DateFormat formater, PropertyDetail field, String val, String op, int count) throws OpenEditException
	{
		Term t = null;
		try
		{
			if (op.startsWith("before"))
			{
				Date d = null;
				if (op.length() > "before".length())
				{
					d = new Date();
					int len = Integer.parseInt(op.substring("before".length()));
					GregorianCalendar cal = new GregorianCalendar();
					cal.setTime(d);
					cal.add(GregorianCalendar.DAY_OF_MONTH, 0 - len); // subtract
																		// start
																		// date
					t = search.addBetween(field, cal.getTime(), d);
					t.setOperation(op);
					
				}
				else if (val != null && !"".equals(val))
				{
					d = formater.parse(val);
					t = search.addBefore(field, d);
				}
				if (t != null)
				{
					search.setProperty(t.getId(), val);
					search.setProperty("datedirection" + field, op);
				}
			}
			else if ("after".equals(op) && val != null && !"".equals(val))
			{
				Date d = formater.parse(val);
				t = search.addAfter(field, d);
				search.setProperty("datedirection" + field, "after");
				search.setProperty(t.getId(), val);
			}
			else if ("equals".equals(op) && val != null && !"".equals(val))
			{
				Date d = formater.parse(val);
				
				Calendar c = new GregorianCalendar();

				c.setTime(d);
				c.set(Calendar.HOUR_OF_DAY, 0);
				c.set(Calendar.MILLISECOND, 0);
				c.set(Calendar.MINUTE, 0);

				Calendar c2 = new GregorianCalendar();
				c2.setTime(c.getTime());
				c2.add(Calendar.DAY_OF_YEAR, 1);

				t = search.addBetween(field, c.getTime(), c2.getTime());
				search.setProperty(t.getId(), val);
			}
			else if ("betweendates".equals(op))
			{
				String[] beforeStrings = inPageRequest.getRequestParameters(field + ".before");
				String[] afterStrings = inPageRequest.getRequestParameters(field + ".after");
				
				String beforeString = null, afterString = null;
				if (beforeStrings != null && beforeStrings.length > count)
				{
					beforeString = beforeStrings[count];
					afterString = afterStrings[count]; //We should not get array out of bounds
				}
				if (beforeString == null)
				{
					beforeString = inPageRequest.getRequestParameter(field + ".before");
					afterString = inPageRequest.getRequestParameter(field + ".after");
				}
				if(beforeString.length() == 0){
					beforeString = null;
				}
				if(afterString.length() == 0){
					afterString = null;
				}
				if (beforeString == null && afterString == null)
				{
						//?
				}
				else if (beforeString == null)
				{
					Date after = formater.parse(afterString);
					t = search.addAfter(field, after);
					search.setProperty(t.getId() + ".after", beforeString);
				}
				else if (afterString == null)
				{
					Date before = formater.parse(beforeString);
					t = search.addBefore(field, before);
					search.setProperty(t.getId() + ".before", beforeString);
				}
				else
				{
					Date before = formater.parse(beforeString);
					Date after = formater.parse(afterString);
					t = search.addBetween(field, after, before);
					search.setProperty(t.getId() + ".before", beforeString);
					search.setProperty(t.getId() + ".after", afterString);
				}
			}
			else if ("betweenages".equals(op))
			{
				String beforeString = inPageRequest.getRequestParameter(field + ".before");
				String afterString = inPageRequest.getRequestParameter(field + ".after");

				if (beforeString == null && afterString == null)
				{

				}
				else if (beforeString == null)
				{
					Calendar rightNow = Calendar.getInstance();
					int before = Integer.parseInt(afterString);
					rightNow.add(Calendar.YEAR, (-1 * before));
					Date after = rightNow.getTime();
					t = search.addBefore(field, after);
					search.setProperty(t.getId() + ".after", afterString);

				}
				else if (afterString == null)
				{
					Calendar rightNow = Calendar.getInstance();
					int after = Integer.parseInt(beforeString);
					rightNow.add(Calendar.YEAR, (-1 * after));
					Date before = rightNow.getTime();
					t = search.addAfter(field, before);
					search.setProperty(t.getId() + ".before", beforeString);
				}
				else
				{
					// before
					Calendar rightNow = Calendar.getInstance();
					int a = Integer.parseInt(afterString);
					rightNow.add(Calendar.YEAR, (-1 * a));
					Date before = rightNow.getTime();
					// after
					rightNow = Calendar.getInstance();
					int b = Integer.parseInt(beforeString);
					rightNow.add(Calendar.YEAR, (-1 * b));
					Date after = rightNow.getTime();
					// add search term
					t = search.addBetween(field, after, before);
					search.setProperty(t.getId() + ".after", afterString);
					search.setProperty(t.getId() + ".before", beforeString);
				}
			}
		}
		catch (ParseException ex)
		{
			throw new OpenEditException(ex);
		}
		if (t != null)
		{
			t.addParameter("op",op); //TODO make these match with standard operations?
		}
		return t;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * org.openedit.data.Searcher#addActionFilters(com.openedit.WebPageRequest,
	 * com.openedit.hittracker.SearchQuery)
	 */

	public SearchQuery addActionFilters(WebPageRequest inReq, SearchQuery search)
	{
		Configuration config = inReq.getCurrentAction().getConfig();
		Configuration matchesConfig = config.getChild("matches");
		if (matchesConfig != null)
		{
			for (Iterator iterator = matchesConfig.getChildren().iterator(); iterator.hasNext();)
			{
				Configuration child = (Configuration) iterator.next();
				String fieldName = child.getName();
				String value = child.getValue();
				PropertyDetail detail = new PropertyDetail();
				detail.setCatalogId(getCatalogId());
				detail.setId(fieldName);
				search.addMatches(detail, value);
				search.setProperty(fieldName, value); // The last one wins, only
													// for matches
			}
		}
		Configuration notConfig = config.getChild("not");
		if (notConfig != null)
		{
			for (Iterator iterator = notConfig.getChildren().iterator(); iterator.hasNext();)
			{
				Configuration child = (Configuration) iterator.next();
				String fieldName = child.getName();
				String value = child.getValue();
				PropertyDetail detail = new PropertyDetail();
				detail.setCatalogId(getCatalogId());
				detail.setId(fieldName);

				search.addNot(detail, value);
			}
		}
		Configuration sortConfig = config.getChild("sortby");
		if (sortConfig != null)
		{
			search.setSortBy(sortConfig.getValue());
		}
		return search;
	}

	public HitTracker loadPageOfSearch(WebPageRequest inPageRequest) throws OpenEditException
	{
		HitTracker tracker = loadHits(inPageRequest);
		if( tracker == null)
		{
			return null;
		}
		// This is where we handle changing the number of hits per page
		String hitsperpage = inPageRequest.getRequestParameter("hitsperpage");
		if (tracker != null && hitsperpage != null)
		{
			int numhitsperpage = Integer.parseInt(hitsperpage);
			tracker.setHitsPerPage(numhitsperpage);
		}

		String page = inPageRequest.getRequestParameter("page");
		int totalPages = tracker.getTotalPages();
		if (page != null)
		{
			int jumpToPage = Integer.parseInt(page);
			if (jumpToPage <= totalPages && jumpToPage > 0)
			{
				tracker.setPage(jumpToPage);
			}
			else
			{
				tracker.setPage(1);
			}
			return tracker;
		}

		String nav = inPageRequest.getRequestParameter("nav");
		if (nav != null)
		{
			if ("next".equals(nav))
			{
				int jumpToPage = tracker.getPage();
				jumpToPage++;
				if (jumpToPage <= totalPages && jumpToPage > 0)
				{
					tracker.setPage(jumpToPage);
				}
			}
			else if ("previous".equals(nav))
			{
				int jumpToPage = tracker.getPage();
				jumpToPage--;
				if (jumpToPage <= totalPages && jumpToPage > 0)
				{
					tracker.setPage(jumpToPage);
				}
			}
		}
		return tracker;
	}
	/**
	 * @deprecated use changeSort which checks hitssessionid
	 * @param inReq
	 * @param sort
	 * @param hitsname
	 * @throws OpenEditException
	 */
	protected void changeSort(WebPageRequest inReq, String sort, String hitsname) throws OpenEditException
	{
		HitTracker hits = loadHits(inReq, hitsname);

		if (hits != null)
		{
			SearchQuery group = hits.getSearchQuery();
			group.setSortBy(sort);
			hits.setIndexId(hits.getIndexId() + sort); // Causes the hits to be
														// reloaded
			// inReq.removeSessionValue("hits");
			cachedSearch(inReq, group);
		}
	}

	public void changeSort(WebPageRequest inReq) throws OpenEditException
	{
		String sort = inReq.getRequestParameter("sortby");
		HitTracker hits = loadHits(inReq);

		if (hits != null && sort != null)
		{
			SearchQuery group = hits.getSearchQuery();
			if (!sort.equals(group.getSortBy()))
			{
				group.setSortBy(sort);
				hits.setIndexId(hits.getIndexId() + sort); // Causes the hits to be	rerun													// reloaded
				cachedSearch(inReq, group);
				UserProfile pref = (UserProfile) inReq.getUserProfile();
				if (pref != null)
				{
					pref.setSortForSearchType(hits.getSearchQuery().getResultType(), sort);
				}
			}
		}
	}
	
	
	public void updateFilters(WebPageRequest inReq) throws OpenEditException
	{
		HitTracker hits = loadHits(inReq);

		if (hits != null)
		{
			String toadd = inReq.getRequestParameter("filtertype");
			SearchQuery query = hits.getSearchQuery();
			if( toadd != null)
			{
				String toaddvalue = inReq.getRequestParameter("filtervalue");
				String toaddlabel = inReq.getRequestParameter("filterlabel");
				query.addFilter(toadd,toaddvalue,toaddlabel);
			}
			else
			{
				String toremove = inReq.getRequestParameter("removefilter");
				String asterisk = "*";
				if(toremove.equals(asterisk))
				{
					query.clearFilters();
				}
				else
				{
					query.removeFilter(toremove);
				}
				
			}
				hits.setIndexId(hits.getIndexId() + 1); // Causes the hits to
			// be // reloaded
			cachedSearch(inReq, query);
		}
		
	}
	
	
	protected SearchQuery createSearchQuery(String inQueryString, WebPageRequest inPageRequest)
	{
		String[] array = inQueryString.split(";");
		return createSearchQuery(array, inPageRequest);
	}

	protected SearchQuery createSearchQuery(String[] inQueryStrings, WebPageRequest inReq)
	{
		SearchQuery query = createSearchQuery();

		for (int i = 0; i < inQueryStrings.length; i++)
		{
			String querystring = inQueryStrings[i];
			if (!"none".equals(querystring))
			{
				String[] parts = querystring.split(":");
				if (parts.length >= 2)
				{
					if (parts[0].startsWith("-"))
					{
						PropertyDetail detail = getDetail(parts[0].substring(1), inReq);
						addTerm(query, detail, parts[1], "not");
					}
					else if (parts[0].startsWith("~"))
					{
						PropertyDetail detail = getDetail(parts[0].substring(1), inReq);
						addTerm(query, detail, parts[1], "matches");
					}
					else
					{
						PropertyDetail detail = getDetail(parts[0], inReq);
						addTerm(query, detail, parts[1], "exact");
					}
				}
			}
		}
		
		return query;
	}

	/*
	 * Shows only the hits that have a certain property
	 */
	public void addChildQuery(WebPageRequest inReq) throws OpenEditException
	{
		HitTracker hits = loadHits(inReq);

		if (hits != null)
		{
			SearchQuery group = hits.getSearchQuery();

			String[] querystrings = inReq.getRequestParameters("childquery");
			if (querystrings != null)
			{
				group.getChildren().clear();
				
				for (int i = 0; i < querystrings.length; i++)
				{
					SearchQuery child = createSearchQuery(querystrings[i], inReq);
					if (child != null && child.getProperties().size() > 0)
					{
						group.addChildQuery(child);
					}	
				}
			
				hits.setIndexId(hits.getIndexId() + "1"); // Causes the hits to be
				// reloaded
			}

			// inReq.removeSessionValue("hits");
			cachedSearch(inReq, group);
		}
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.openedit.data.Searcher#getPropertyDetailsArchive()
	 */
	public PropertyDetailsArchive getPropertyDetailsArchive()
	{
		if (fieldPropertyDetailsArchive == null)
		{
			fieldPropertyDetailsArchive = (PropertyDetailsArchive) getSearcherManager().getModuleManager().getBean(getCatalogId(), "propertyDetailsArchive");
		}
		if(!fieldPropertyDetailsArchive.getCatalogId().equals(getCatalogId())){
			fieldPropertyDetailsArchive = (PropertyDetailsArchive) getSearcherManager().getModuleManager().getBean(getCatalogId(), "propertyDetailsArchive");
			fieldPropertyDetailsArchive.setCatalogId(getCatalogId());
		}
		return fieldPropertyDetailsArchive;
	}

	public PropertyDetailsArchive getFieldArchive()
	{
		return getPropertyDetailsArchive();
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see
	 * org.openedit.data.Searcher#setPropertyDetailsArchive(org.openedit.data
	 * .PropertyDetailsArchive)
	 */
	public void setPropertyDetailsArchive(PropertyDetailsArchive inPropertyDetailsArchive)
	{
		fieldPropertyDetailsArchive = inPropertyDetailsArchive;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.openedit.data.Searcher#getSearcherManager()
	 */
	public SearcherManager getSearcherManager()
	{
		return fieldSearcherManager;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @seeorg.openedit.data.Searcher#setSearcherManager(org.openedit.data.
	 * SearcherManager)
	 */
	public void setSearcherManager(SearcherManager inSearcherManager)
	{
		fieldSearcherManager = inSearcherManager;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.openedit.data.Searcher#getFieldName()
	 */
	/**
	 * @deprecated Use {@link #getSearchType()} instead
	 */
	public String getFieldName()
	{
		return getSearchType();
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.openedit.data.Searcher#getFieldName()
	 */
	public String getSearchType()
	{
		return fieldSearchType;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.openedit.data.Searcher#setFieldName(java.lang.String)
	 */
	/**
	 * @deprecated Use {@link #setSearchType(String)} instead
	 */
	public void setFieldName(String inFieldName)
	{
		setSearchType(inFieldName);
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.openedit.data.Searcher#setFieldName(java.lang.String)
	 */
	public void setSearchType(String inSearchType)
	{
		fieldSearchType = inSearchType;
	}

	protected void fireSearchEvent(WebEvent inEvent)
	{
		if (fieldWebEventListener != null)
		{
			fieldWebEventListener.eventFired(inEvent);
		}
	}

	protected WebEventListener getWebEventListener()
	{
		return fieldWebEventListener;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.openedit.data.Searcher#getCatalogId()
	 */
	public String getCatalogId()
	{
		return fieldCatalogId;
	}

	/*
	 * (non-Javadoc)
	 * 
	 * @see org.openedit.data.Searcher#setCatalogId(java.lang.String)
	 */
	public void setCatalogId(String inCatalogId)
	{
		fieldCatalogId = inCatalogId;
	}

	public void setWebEventListener(WebEventListener inWebEventListener)
	{
		fieldWebEventListener = inWebEventListener;
	}

	public List getProperties()
	{
		PropertyDetails details = getPropertyDetailsArchive().getPropertyDetailsCached(getSearchType());
		if (details == null)
		{
			return null;
		}
		return details.getDetails();
	}

	public List getIndexProperties()
	{
		PropertyDetails details = getPropertyDetailsArchive().getPropertyDetailsCached(getSearchType());
		if (details == null)
		{
			return null;
		}
		return details.findIndexProperties();
	}

	public List getSearchProperties(User inUser)
	{
		List details = getDetailsForView(getSearchType() + "/" + getSearchType() + "search", inUser);
		if (details == null)
		{
			PropertyDetail det = getDetail("name");
			details = new ArrayList();
			if (det != null)
			{
				details.add(det);
			}
			det = getDetail("id");
			if (det != null)
			{
				details.add(det);
			}
		}
		return details;
	}

	public List getStoredProperties()
	{
		PropertyDetails details = getPropertyDetailsArchive().getPropertyDetailsCached(getSearchType());
		if (details == null)
		{
			return null;
		}
		return details.findStoredProperties();
	}
	
	public List getKeywordProperties()
	{
		PropertyDetails details = getPropertyDetailsArchive().getPropertyDetailsCached(getSearchType());
		if (details == null)
		{
			return null;
		}
		return details.findKeywordProperties();
	}
	
	public Data searchByQuery(SearchQuery inQuery)
	{
		HitTracker hits = search(inQuery);
		hits.setHitsPerPage(1);
		return (Data)hits.first();
	}		
	public Object searchByField(String inField, String inValue)
	{
		SearchQuery query = createSearchQuery();
		query.addMatches(inField, inValue);
		HitTracker hits = search(query);
		hits.setHitsPerPage(1);
		return hits.first();
	}
	public HitTracker searchByIds(Collection<String> inIds)
	{
		SearchQuery query = createSearchQuery();
		String[] args = inIds.toArray(new String[inIds.size()]);
		PropertyDetail detail = getDetail("id");
		query.addOrsGroup(detail, args);
		HitTracker hits = search(query);
		return hits;
	}
	public Object searchById(String inId)
	{
		if(inId == null || inId.length() == 0)
		{
			return null;
		}
		return searchByField("id",inId);
	}

	public Data createNewData()
	{
		String classname = getNewDataName();
		if( classname == null)
		{
			BaseData data = new BaseData();
			return data;
		}
		return (Data)getModuleManager().getBean( getNewDataName());
	}



	/**
	 * @deprecated use getDetailsForView
	 * @param inView
	 * @param inUser
	 * @return
	 * @throws Exception
	 */
	public List getDataProperties(String inView, User inUser) throws Exception
	{
		return getPropertyDetailsArchive().getDataProperties(getSearchType(), inView, inUser);
	}
	public List getDetailsForView(String inView)
	{
		return getDetailsForView(inView, (UserProfile)null);
	}
	//Some problem with Velocity, if the user is null then it does not resolve this method
	/**
	 * @deprecated remove this by April 1 2013
	 */
	public List getDetailsForView(String inView, User inUser)
	{
		List results = getPropertyDetailsArchive().getDataProperties(getSearchType(), inView, inUser);
		return results;
	}
	
	public List getDetailsForView(String inView, UserProfile inProfile)
	{
//		List results = getPropertyDetailsArchive().getDataProperties(getSearchType(), inView, inProfile);
//		return results;
		View view = getPropertyDetailsArchive().getView(getSearchType(), inView, inProfile);
		return view;
	}

	/**
	 * This is old code and should not be used any more
	 */
	public PropertyDetail getDetailForView(String inView, String inFieldName, User inUser)
	{
		return getDetail(inFieldName);
//		PropertyDetails details = getPropertyDetailsArchive().getPropertyDetailsCached(getSearchType());
//		if (details == null)
//		{
//			return null;
//		}
		
//		getPropertyDetailsArchive().getDetails(details,inView,inUser);
//
//		
//		PropertyDetail detail = null;
//		if( inView != null )
//		{
//			detail = getPropertyDetailsArchive().getDetail(details, inView, inFieldName, inUser);
//		}
//		if (detail == null)
//		{
//			detail = details.getDetail(inFieldName);
//		}
//		return detail;
	}

	public List getDetailsForFields(String[] headers)
	{
		PropertyDetails details = getPropertyDetails();
		List results = new ArrayList(headers.length);
		for (int i = 0; i < headers.length; i++)
		{
			String field = headers[i];
			PropertyDetail detail = details.getDetail(field);
			if (detail != null)
			{
				results.add(detail);
			}

		}
		return results;
	}

	public PropertyDetails getPropertyDetails()
	{
		PropertyDetails details = getPropertyDetailsArchive().getPropertyDetailsCached(getSearchType());
		return details;
	}
	
	public Collection<PropertyDetail> getUserPropertyDetails()
	{
		PropertyDetails details = getPropertyDetails();
		List<PropertyDetail> sublist = new ArrayList<PropertyDetail>(details.size());
		for (Iterator iterator = details.iterator(); iterator.hasNext();)
		{
			PropertyDetail detail = (PropertyDetail) iterator.next();
			String val = detail.get("internalfield");
			if( val == null || val.equals("false") )
			{
				sublist.add(detail);				
			}
		}
		Collections.sort(sublist);
		return sublist;
	}

	public HitTracker getAllHits()
	{
		return getAllHits(null);
	}

	public boolean isFireEvents()
	{
		return fieldFireEvents;
	}

	public void setFireEvents(boolean inFireEvents)
	{
		fieldFireEvents = inFireEvents;
	}

	public PropertyDetail getDetail(String inId)
	{
		PropertyDetails details = getPropertyDetailsArchive().getPropertyDetailsCached(getSearchType());
		if (details == null)
		{
			return null;
		}
		PropertyDetail detail = details.getDetail(inId);
		return detail;
	}

	public HitTracker getAllHits(WebPageRequest inReq)
	{
		SearchQuery q = createSearchQuery();
		q.addMatches("*");
		if (inReq != null)
		{
			String sort = inReq.getRequestParameter("sortby");
			if (sort == null)
			{
				sort = inReq.findValue("sortby");
			}
			if (sort != null)
			{
				q.setSortBy(sort);
			}
			return cachedSearch(inReq, q);
		} else{
			return search(q);
		}
		
	}

	public void saveCompositeData(CompositeData inData, User inUser)
	{
		for (Iterator iterator = inData.iterator(); iterator.hasNext();)
		{
			Data data = (Data) iterator.next();
			saveData(data, inUser);
		}
	}

	public String lookupValue(String val, WebPageRequest inReq, Data data)
	{
		String value = inReq.getRequestParameter(val);
		if (value != null)
		{
			return value;

		}
		if (data != null)
		{
			value = data.get(val);
		}
		return value;
	}

	/*
	 * Check for a more recent index
	 * 
	 * @see
	 * org.openedit.data.Searcher#checkCurrent(com.openedit.hittracker.HitTracker
	 * )
	 */
	public HitTracker checkCurrent(WebPageRequest inReq, HitTracker tracker) throws OpenEditException
	{
		if (tracker != null && tracker.getQuery() != null)
		{
			String forcerun = inReq.getRequestParameter("cache");
			if( forcerun != null && !Boolean.parseBoolean(forcerun))
			{
				tracker.setIndexId(tracker.getIndexId() + "1"); // Causes the hits to be
			}

			if (hasChanged(tracker))
			{
				int oldNum = tracker.getPage();
				SearchQuery newQuery = tracker.getSearchQuery().copy();
				HitTracker tracker2 = cachedSearch(inReq, newQuery);
				if( tracker2 != null)
				{
					tracker2.setPage(oldNum);
					tracker2.setHitsPerPage(tracker.getHitsPerPage());
				}
				return tracker2;
			}
		}
		return tracker;
	}

	protected void fireDataEditEvent(WebPageRequest inReq, Data object)
	{

		if (fieldWebEventListener == null)
		{
			return;
		}
		StringBuffer changes = new StringBuffer();
		String[] fields = inReq.getRequestParameters("field");
		if (fields == null)
		{
			return;
		}
//		if (composite != null)
//		{
//			changes.append("MutliEdit->");
//		}
		for (int i = 0; i < fields.length; i++)
		{
			String field = fields[i];
			String value = inReq.getRequestParameter(field + ".value");
//			if (composite != null)
//			{
//				String compositeVal = composite.get(field);
//				if (compositeVal == value)
//				{
//					continue;
//				}
//			}
			String oldval = object.get(field);

			if (oldval == null)
			{
				oldval = "";
			}
			if (value == null)
			{
				value = "";
			}

			if (!value.equals(oldval))
			{
				PropertyDetail detail = getDetail(field);
				if (detail != null && detail.isList())
				{
					Searcher listSearcher = getSearcherManager().getListSearcher(detail);
					Data data = (Data) listSearcher.searchById(oldval);
					if (data != null)
					{
						oldval = data.getName();
					}
					data = (Data) listSearcher.searchById(value);
					if (data != null)
					{
						value = data.getName();
					}

				}
				if (changes.length() > 0)
				{
					changes.append(", ");
				}
				changes.append(field + ": " + oldval + " -> " + value);
			}
		}

		if (changes.length() > 0)
		{
			WebEvent event = new WebEvent();
			event.setCatalogId(getCatalogId());
			String type = getSearchType();
			event.setSearchType(type);
			event.setSource(this);
			//assetedit

			//			event.setSearchType("assetedit");
			//			event.setSource(this);
			//			event.setOperation("assetedit");
			//			event.setSourcePath(asset.getSourcePath());

			//			event.setProperty("assetid", asset.getId());
			//			event.setProperty("assetname", asset.getName());
			//			event.setProperty("changes", changes.toString());

			event.setOperation(getSearchType() + "/edit");
			event.setSourcePath(object.getSourcePath());
			event.setProperty("id", object.getId());
			//aka "changes"
			event.setProperty("details", changes.toString());
			event.setUser(inReq.getUser());
			getWebEventListener().eventFired(event);
		}
	}

	/*
	 * public WebEventListener getWebEventListener() { return
	 * fieldWebEventListener; }
	 * 
	 * public void setWebEventListener(WebEventListener inWebEventListener) {
	 * fieldWebEventListener = inWebEventListener; }
	 */
	public void saveDetails(WebPageRequest inReq, String[] fields, Data data, String id)
	{
//		// This might be a productid of multiple products. We need to create
//		// lots of data objects
//		if (id != null && id.startsWith("multiedit:"))
//		{
//			data = (Data) inReq.getSessionValue(id);
//		}
//
		if (data instanceof CompositeData)
		{
			CompositeData target = (CompositeData) data;
			for (Iterator iterator = target.iterator(); iterator.hasNext();)
			{
				Data real = (Data) iterator.next();
				fireDataEditEvent(inReq, real);
				data = updateData(inReq, fields, real);
				saveData(real, inReq.getUser());
			}
		}
		else
		{
			fireDataEditEvent(inReq, data);
			data = updateData(inReq, fields, data);
			saveData(data, inReq.getUser());
		}
		inReq.putPageValue("message", data.getId() + " is saved");
		inReq.putPageValue("data", data);
	}

	public Data updateData(WebPageRequest inReq, String[] fields, Data data)
	{
		if (fields != null)
		{
			for (int i = 0; i < fields.length; i++)
			{
				String field = fields[i];

				String[] values = inReq.getRequestParameters(field + ".values");
				if( values == null )
				{
					values = inReq.getRequestParameters(field + ".value");
				}
				if (values != null && values.length > 1)
				{
					HashSet<String> hash = new HashSet<String>();
					for (String val:values){
						hash.add(val);
					}
					StringBuilder buf = new StringBuilder();
					Iterator<String> itr = hash.iterator();
					while (itr.hasNext()){
						buf.append(itr.next());
						if (itr.hasNext()) buf.append(" | ");
					}
					data.setProperty(field, buf.toString());
				}
				else if (values != null)
				{
					String val = values[0];
					
					PropertyDetail detail = getDetail(field);
					if( detail != null)
					{
						String hour = inReq.getRequestParameter(field + ".hour");
						String minute = inReq.getRequestParameter(field + ".minute");
						if(hour != null && minute != null)
						{
							val = val + " " + hour + ":" + minute;
							val = DateStorageUtil.getStorageUtil().formatForStorage(val, "yyyy-MM-dd HH:mm");
						}
						else if( detail.isDate() )
						{
							if( val.length() == 10) //We assume US format or Storage Format
							{
								val = DateStorageUtil.getStorageUtil().formatForStorage(val, "yyyy-MM-dd");
							}
						}
					}
					
					data.setProperty(field, val);
				}
				else
				{
					data.setProperty(field, null);
				}
			}
		}
		return data;
	}

	public String nextId()
	{
		throw new IllegalAccessError("nextId Not implemented");
	}

	public String getViewLabel(String inView)
	{
		return getPropertyDetailsArchive().getViewLabel(inView);
	}
	public Data uniqueResult(SearchQuery inQ)
	{
		HitTracker tracker = search(inQ);
		Iterator iter  = tracker.iterator();
		if( iter.hasNext() )
		{
			return (Data)iter.next();
		}
		return null;
	}

	/**
	 * @deprecated
	 * @param inData
	 * @param inUser
	 */
	public void saveData(Object inData, User inUser)
	{
		saveData((Data) inData, inUser);
	}


	public void saveData(Data inData, User inUser)
	{
		throw new OpenEditException("Save not implemented for " + getSearchType());
	}
	
	
	
	public LockManager getLockManager(){
	return getSearcherManager().getLockManager();	
	}
}