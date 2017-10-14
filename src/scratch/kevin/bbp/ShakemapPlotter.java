package scratch.kevin.bbp;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.util.List;
import java.util.zip.ZipException;

import org.dom4j.Document;
import org.dom4j.DocumentException;
import org.dom4j.Element;
import org.opensha.commons.data.Site;
import org.opensha.commons.data.function.DiscretizedFunc;
import org.opensha.commons.data.xyz.GriddedGeoDataSet;
import org.opensha.commons.exceptions.GMT_MapException;
import org.opensha.commons.geo.GriddedRegion;
import org.opensha.commons.mapping.gmt.GMT_Map;
import org.opensha.commons.mapping.gmt.elements.GMT_CPT_Files;
import org.opensha.commons.util.XMLUtils;
import org.opensha.commons.util.cpt.CPT;
import org.opensha.sha.earthquake.EqkRupture;
import org.opensha.sha.imr.AttenRelRef;
import org.opensha.sha.imr.ScalarIMR;
import org.opensha.sha.imr.param.IntensityMeasureParams.SA_Param;

import com.google.common.base.Preconditions;

import scratch.UCERF3.analysis.FaultBasedMapGen;
import scratch.kevin.bbp.BBP_Module.VelocityModel;
import scratch.kevin.bbp.BBP_SimZipLoader.BBP_ShakeMapSimZipLoader;

public class ShakemapPlotter {
	
	public static void plotShakemaps(BBP_ShakeMapSimZipLoader loader, GriddedRegion gridReg, List<BBP_Site> sites,
			String label, File outputDir, String prefix, boolean log, double... periods)
					throws IOException, GMT_MapException {
		plotShakemaps(loader, gridReg, sites, label, outputDir, prefix, log,  null, null, null, periods);
	}
	
	public static void plotShakemaps(BBP_ShakeMapSimZipLoader loader, GriddedRegion gridReg, List<BBP_Site> sites,
			String label, File outputDir, String prefix, boolean log, ScalarIMR gmpe, EqkRupture rup, VelocityModel vm,
			double... periods) throws IOException, GMT_MapException {
		GriddedGeoDataSet[] xyzs = load(loader, gridReg, sites, periods);
		
		GriddedGeoDataSet[] gmpes = null;
		if (gmpe != null)
			gmpes = calcGMPE(gmpe, rup, vm, gridReg, sites, periods);
		
		CPT cpt = GMT_CPT_Files.MAX_SPECTRUM.instance();
		
		for (int p=0; p<periods.length; p++) {
			System.out.println("Plotting "+(float)periods[p]+"s map");
			double max = xyzs[p].getMaxZ();
			if (gmpes != null)
				max = Math.max(max, gmpes[p].getMaxZ());
			if (log) {
				xyzs[p].log10();
				if (gmpes != null)
					gmpes[p].log10();
				double logMax = Math.log10(max);
				double cleanLogMax = Math.ceil(logMax);
				if (cleanLogMax - logMax > 0.5)
					cleanLogMax -= 0.5;
				cpt = cpt.rescale(-3d, cleanLogMax);
			} else {
				max = Math.ceil(max*5d)/5d;
				cpt = cpt.rescale(0d, max);
			}
			GMT_Map map = new GMT_Map(gridReg, xyzs[p], gridReg.getSpacing(), cpt);
			map.setRescaleCPT(false);
			map.setLogPlot(false);
			map.setUseGMTSmoothing(false);
			map.setBlackBackground(false);
			map.setJPGFileName(null);
			map.setPDFFileName(null);
			if (log) {
				double cptDelta = cpt.getMaxValue() - cpt.getMinValue();
				if (cptDelta > 2)
					map.setCPTCustomInterval(1d);
				else
					map.setCPTCustomInterval(0.5);
			}
			
			String pStr;
			if (periods[p] == Math.round(periods[p]))
				pStr = (int)periods[p]+"";
			else
				pStr = (float)periods[p]+"";
			
			map.setCustomLabel("Log10 "+label+" "+pStr+"s SA (RotD50)");
			String myPrefix = prefix+"_"+pStr+"s";
			
			FaultBasedMapGen.plotMap(outputDir, myPrefix, false, map);
			
			if (gmpe != null) {
				// plot GMPE
				map.setGriddedData(gmpes[p]);
				map.setCustomLabel("Log10 "+gmpe.getShortName()+" "+pStr+"s SA (RotD50)");
				myPrefix = prefix+"_"+pStr+"s_"+gmpe.getShortName();
				
				FaultBasedMapGen.plotMap(outputDir, myPrefix, false, map);
				
				// now ratio
				CPT ratioCPT = GMT_CPT_Files.GMT_POLAR.instance().rescale(-2, 2);
				GriddedGeoDataSet ratioData = new GriddedGeoDataSet(gridReg, false);
				if (!log) {
					// log it
					xyzs[p].log10();
					gmpes[p].log10();
				}
				for (int i=0; i<ratioData.size(); i++)
					ratioData.set(i, xyzs[p].get(i)/gmpes[p].get(i));
				
				map.setGriddedData(ratioData);
				map.setCustomLabel("Log10 Ratio "+pStr+"s SA (RotD50)");
				map.setCpt(ratioCPT);
				map.setCPTCustomInterval(0.5);
				myPrefix = prefix+"_"+pStr+"s_"+gmpe.getShortName()+"_ratio";
				
				FaultBasedMapGen.plotMap(outputDir, myPrefix, false, map);
			}
		}
	}
	
	private static GriddedGeoDataSet[] load(BBP_ShakeMapSimZipLoader loader, GriddedRegion gridReg,
			List<BBP_Site> sites, double[] periods) throws IOException {
		Preconditions.checkState(gridReg.getNodeCount() == sites.size(), "Sites/gridded region mismatch!");
		GriddedGeoDataSet[] ret = new GriddedGeoDataSet[periods.length];
		for (int i=0; i<periods.length; i++)
			ret[i] = new GriddedGeoDataSet(gridReg, false);
		
		System.out.println("Loading shakemaps for "+periods.length+" periods...");
		for (int i=0; i<sites.size(); i++) {
			DiscretizedFunc rd50 = loader.readRotD50(i);
			
			for (int p=0; p<periods.length; p++)
				ret[p].set(i, rd50.getInterpolatedY(periods[p]));
		}
		System.out.println("DONE");
		
		return ret;
	}
	
	private static GriddedGeoDataSet[] calcGMPE(ScalarIMR gmpe, EqkRupture rup, VelocityModel vm,
			GriddedRegion gridReg, List<BBP_Site> sites, double[] periods) {
		gmpe.setParamDefaults();
		
		gmpe.setEqkRupture(rup);
		gmpe.setIntensityMeasure(SA_Param.NAME);
		
		Preconditions.checkState(gridReg.getNodeCount() == sites.size(), "Sites/gridded region mismatch!");
		GriddedGeoDataSet[] ret = new GriddedGeoDataSet[periods.length];
		for (int i=0; i<periods.length; i++)
			ret[i] = new GriddedGeoDataSet(gridReg, false);
		
		System.out.println("Calculating GMPE shakemaps for "+periods.length+" periods...");
		for (int i=0; i<sites.size(); i++) {
			Site site = sites.get(i).buildGMPE_Site(vm);
			gmpe.setSite(site);
			
			for (int p=0; p<periods.length; p++) {
				SA_Param.setPeriodInSA_Param(gmpe.getIntensityMeasure(), periods[p]);
				ret[p].set(i, Math.exp(gmpe.getMean()));
			}
		}
		System.out.println("DONE");
		
		return ret;
	}
	
	public static GriddedRegion loadGriddedRegion(File sitesXML) throws MalformedURLException, DocumentException {
		Document doc = XMLUtils.loadDocument(sitesXML);
		Element root = doc.getRootElement();
		Element regEl = root.element(GriddedRegion.XML_METADATA_NAME);
		return GriddedRegion.fromXMLMetadata(regEl);
	}

	public static void main(String[] args) throws ZipException, IOException, DocumentException, GMT_MapException {
		File dir = new File("/data/kevin/bbp/parallel/2017_10_12-rundir2194_long-event136704-shakemap-noHF");
		File zipFile = new File(dir, "results.zip");
		List<BBP_Site> sites = BBP_Site.readFile(dir);
		BBP_ShakeMapSimZipLoader loader = new BBP_ShakeMapSimZipLoader(zipFile, sites);
		
		File sitesXML = new File(dir, "sites.xml");
		GriddedRegion gridReg = loadGriddedRegion(sitesXML);
		
		FaultBasedMapGen.LOCAL_MAPGEN = true;
		
		plotShakemaps(loader, gridReg, sites, "Test Map", new File("/tmp"), "shakemap", true, 1d, 2d, 5d);
	}

}
