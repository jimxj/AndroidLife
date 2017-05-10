package com.camnter.newlife.ui.mvvm.view;

import android.databinding.ViewDataBinding;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import com.camnter.mvvm.MVVMViewAdapter;
import com.camnter.newlife.R;
import com.camnter.newlife.bean.ratingrank.RatingFund;
import com.camnter.newlife.core.activity.BaseMVVMActivity;
import com.camnter.newlife.databinding.ActivityRatingRankBinding;
import com.camnter.newlife.ui.mvvm.mock.Injection;
import com.camnter.newlife.ui.mvvm.vm.RatingRankViewModel;
import com.camnter.newlife.widget.titilebar.TitleBar;

/**
 * Description：RatingRankActivity
 * Created by：CaMnter
 */

public class RatingRankActivity extends BaseMVVMActivity {

    private ActivityRatingRankBinding binding;
    private RatingRankViewModel viewModel;


    @Override protected int getLayoutId() {
        return R.layout.activity_rating_rank;
    }


    @Override
    protected void onCastingContentBinding(@NonNull ViewDataBinding contentBinding) {
        if (contentBinding instanceof ActivityRatingRankBinding) {
            this.binding = (ActivityRatingRankBinding) contentBinding;
        }
    }


    /**
     * on after data binding
     *
     * @param savedInstanceState savedInstanceState
     */
    @Override protected void onAfterDataBinding(@Nullable Bundle savedInstanceState) {
        this.viewModel = new RatingRankViewModel(this,
            Injection.provideRatingRankRepository());
        MVVMViewAdapter<RatingFund> adapter = new MVVMViewAdapter<RatingFund>(this) {
            @Override public int[] getItemLayouts() {
                return new int[] { R.layout.item_rating_ranking };
            }
        };
        adapter.setVHandler(this.viewModel);
        this.viewModel.setAdapter(adapter);
        this.binding.setAdapter(adapter);
        this.binding.setViewModel(this.viewModel);
        this.viewModel.query(this);
    }


    @Override protected boolean getTitleBar(TitleBar titleBar) {
        return false;
    }

}