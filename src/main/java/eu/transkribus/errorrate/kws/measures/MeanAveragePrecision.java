/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package eu.transkribus.errorrate.kws.measures;

import eu.transkribus.errorrate.types.KWS.MatchList;
import java.util.List;

/**
 *
 * @author tobias
 */
public class MeanAveragePrecision extends AveragePrecision {

    @Override
    public double calcMeasure(List<MatchList> matchlists) {
        double sum = 0.0;
        for (MatchList matchList : matchlists) {
            matchList.sort();
            sum += calcAveragePrecision(matchList);
        }
        return sum / matchlists.size();
    }

}
