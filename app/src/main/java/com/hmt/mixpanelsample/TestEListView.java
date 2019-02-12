package com.hmt.mixpanelsample;

import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.BaseExpandableListAdapter;
import android.widget.ExpandableListView;
import android.widget.TextView;

public class TestEListView extends AppCompatActivity implements ExpandableListView.OnGroupClickListener, ExpandableListView.OnChildClickListener, AdapterView.OnItemClickListener {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_test_elist_view);
        mContext = this;
        datas = new String[100];
        for (int i = 0; i < 100; i++) {
            datas[i] = "item : " + i;
        }

        initExpandableLV();
    }


    String[] datas;
    ExpandableListView mExpandableListView;

    Context mContext;

    public String[] groupStrings = {"西游记", "水浒传", "三国演义", "红楼梦"};
    public String[][] childStrings = {
            {"唐三藏", "孙悟空", "猪八戒", "沙和尚"},
            {"宋江", "林冲", "李逵", "鲁智深"},
            {"曹操", "刘备", "孙权", "诸葛亮", "周瑜"},
            {"贾宝玉", "林黛玉", "薛宝钗", "王熙凤"}
    };


    private void initExpandableLV() {

        mExpandableListView = findViewById(R.id.elv);

        mExpandableListView.setOnGroupClickListener(this);

        mExpandableListView.setOnChildClickListener(this);

        TextView header = new TextView(mContext);
        header.setText("this is header 1");
        TextView header2 = new TextView(mContext);
        header2.setText("this is header 2");
        TextView header3 = new TextView(mContext);
        header3.setText("this is header 3");
        TextView header4 = new TextView(mContext);
        TextView header5 = new TextView(mContext);
        TextView header6 = new TextView(mContext);
        header4.setText("this is header 4");

        header.setHeight(200);
        header2.setHeight(200);
        header3.setHeight(200);
        header4.setHeight(200);
        header.setClickable(true);
        header2.setClickable(true);
        header3.setClickable(true);
        header4.setClickable(true);

        mExpandableListView.addHeaderView(header);
        mExpandableListView.addHeaderView(header2);
        mExpandableListView.addHeaderView(header3);
        mExpandableListView.addHeaderView(header4);
        mExpandableListView.addHeaderView(header5);
        mExpandableListView.addHeaderView(header6);


        TextView footer = new TextView(mContext);
        footer.setText("this is footer 1");
        TextView footer2 = new TextView(mContext);
        footer2.setText("this is footer 2");
        TextView footer3 = new TextView(mContext);
        footer3.setText("this is footer 3");

        mExpandableListView.addFooterView(footer);
        mExpandableListView.addFooterView(footer2);
        mExpandableListView.addFooterView(footer3);

        footer.setHeight(200);
        footer2.setHeight(200);
        footer3.setHeight(200);


        mExpandableListView.setOnItemClickListener(this);
        mExpandableListView.setAdapter(new BaseExpandableListAdapter() {
            @Override
            public int getGroupCount() {
                return groupStrings.length;
            }

            @Override
            public int getChildrenCount(int groupPosition) {
                return childStrings[groupPosition].length;
            }

            @Override
            public Object getGroup(int groupPosition) {
                return groupStrings[groupPosition];
            }

            @Override
            public Object getChild(int groupPosition, int childPosition) {
                return childStrings[groupPosition][childPosition];
            }

            @Override
            public long getGroupId(int groupPosition) {
                return groupPosition;
            }

            @Override
            public long getChildId(int groupPosition, int childPosition) {
                return childPosition;
            }

            @Override
            public boolean hasStableIds() {
                return true;
            }

            @Override
            public View getGroupView(int groupPosition, boolean isExpanded, View convertView, ViewGroup parent) {

                GroupViewHolder groupViewHolder = null;
                if (convertView == null) {
                    convertView = LayoutInflater.from(mContext).
                            inflate(R.layout.my_text_view, parent, false);
                    groupViewHolder = new GroupViewHolder();
                    groupViewHolder.tvTitle = convertView.findViewById(R.id.tv_view);
                    convertView.setTag(groupViewHolder);
                } else {
                    groupViewHolder = (GroupViewHolder) convertView.getTag();
                }

                groupViewHolder.tvTitle.setText(getGroup(groupPosition) + "");
                return convertView;
            }

            @Override
            public View getChildView(int groupPosition, int childPosition, boolean isLastChild, View convertView, ViewGroup parent) {
                ChildViewHolder childViewHolder;
                if (convertView == null) {
                    convertView = LayoutInflater.from(mContext).inflate(R.layout.my_text_view, parent, false);
                    childViewHolder = new ChildViewHolder();
                    childViewHolder.tvTitle = (TextView) convertView.findViewById(R.id.tv_view);
                    convertView.setTag(childViewHolder);
                } else {
                    childViewHolder = (ChildViewHolder) convertView.getTag();
                }
                childViewHolder.tvTitle.setText(childStrings[groupPosition][childPosition]);
                childViewHolder.tvTitle.setTextColor(Color.RED);
                return convertView;
            }

            @Override
            public boolean isChildSelectable(int groupPosition, int childPosition) {
                return true;
            }
        });


    }

    IGetTime mTime;

    public void setTime(IGetTime time) {
        mTime = time;
    }

    @Override
    public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
        Log.d("TestEListView", "mExpandableListView.getChildCount():" + mExpandableListView.getChildCount());
        Log.d("TestEListView", "mExpandableListView.getCount():" + mExpandableListView.getCount());
        Log.d("TestEListView", "mExpandableListView.getHeaderViewsCount():" + mExpandableListView.getHeaderViewsCount());
        Log.d("TestEListView", "mExpandableListView.getFooterViewsCount():" + mExpandableListView.getFooterViewsCount());

    }


    interface IGetTime {
        long time();
    }


    @Override
    public boolean onGroupClick(ExpandableListView parent, View v,
                                int groupPosition, long id) {

        return false;
    }

    @Override
    public boolean onChildClick(ExpandableListView parent, View v,
                                int groupPosition, int childPosition, long id) {
        return true;
    }


    static class GroupViewHolder {
        TextView tvTitle;
    }

    static class ChildViewHolder {
        TextView tvTitle;
    }
}
