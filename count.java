import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.Hashtable;
import java.util.Iterator;

public class count {
	private static String []weight;
	public static void main(String args[]) {
		
		String line="";
		try
		{
			BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
			line=in.readLine();
			weight = line.split(" ");
			line=in.readLine();
			while (line!=null)
			{
			    String[] part = line.split(":");
			    String[] term = part[1].split(" ");
			    System.out.print(part[0]+": #AND ( ");
			    for (int i = 0; i < term.length; i++) {
			    	System.out.print("#WSUM ( "+DR(term[i])+" ) ");
			    }
			    System.out.println(")");
			    line=in.readLine();
			}
			in.close();
		} catch (IOException e)
		{
		     e.printStackTrace();
		}
		
	}
	
	private static String DR(String term) {
		return weight[0]+" "+term+".url "+weight[1]+" "+term+".keywords "+weight[2]+" "+term+".title "+weight[3]+" "+term+".body "+weight[4]+" "+term+".inlink";
	}
}
