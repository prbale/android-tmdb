package com.honu.tmdb;

import android.app.Activity;
import android.content.AsyncQueryHandler;
import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.support.v7.widget.GridLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.honu.tmdb.data.MovieContract;
import com.honu.tmdb.rest.ApiError;
import com.honu.tmdb.rest.Movie;
import com.honu.tmdb.rest.MovieResponse;
import com.squareup.picasso.Picasso;

import java.util.ArrayList;
import java.util.List;

import butterknife.Bind;
import butterknife.ButterKnife;
import butterknife.OnClick;

/**
 * Fragment displays grid of movie posters using recycler view
 */
public class MoviePosterGridFragment extends Fragment implements MovieDbApi.MovieListener {

    static final String TAG = MoviePosterGridFragment.class.getSimpleName();

    static final String KEY_MOVIES = "movies";

    @Bind(R.id.recycler_container)
    RecyclerView mRecyclerView;

    Spinner mSortSpinner;

    MovieDbApi mApi;
    MovieGridRecyclerAdapter mAdapter;

    int mSortMethod = SortOption.POPULARITY;

    // communicates selection events back to listener
    OnMovieSelectedListener mListener;

    // interface to communicate movie selection events to MainActivity
    public interface OnMovieSelectedListener {
        public void onMovieSelected(Movie selection, boolean onClick);
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {

        View view = inflater.inflate(R.layout.movie_poster_grid, null);
        ButterKnife.bind(this, view);

        ArrayList<Movie> movies = new ArrayList<>();

        // restore movie list from instance state on orientation change
        if (savedInstanceState != null) {
            mSortMethod = AppPreferences.getCurrentSortMethod(getActivity());
            movies = savedInstanceState.getParcelableArrayList(KEY_MOVIES);
        } else {
            mSortMethod = AppPreferences.getPreferredSortMethod(getActivity());
        }

        mAdapter = new MovieGridRecyclerAdapter();
        mRecyclerView.setAdapter(mAdapter);
        mAdapter.setData(movies);
        mRecyclerView.setLayoutManager(new GridLayoutManager(this.getActivity(), 3));

        // initialize api
        mApi = MovieDbApi.getInstance(getString(R.string.tmdb_api_key));

        // request movies
        if (mAdapter.getItemCount() == 0) {
            if (mSortMethod == SortOption.POPULARITY) {
                mApi.requestMostPopularMovies(this);
            } else {
                mApi.requestHighestRatedMovies(this);
            }
        }

        // check for network connection
        checkNetwork();

        return view;
    }


    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
    }


    @Override
    public void onSaveInstanceState(Bundle outState) {
        outState.putParcelableArrayList(KEY_MOVIES, mAdapter.data);
        super.onSaveInstanceState(outState);
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.movie_poster_grid, menu);

        MenuItem menuItem = menu.findItem(R.id.spin_test);

        // specify layout for the action
        menuItem.setActionView(R.layout.sort_spinner);
        View view = menuItem.getActionView();

        // set custom adapter on spinner
        mSortSpinner = (Spinner) view.findViewById(R.id.spinner_nav);
        mSortSpinner.setAdapter(new SortSpinnerAdapter(this, getActivity(), SortOption.getSortOptions()));
        mSortSpinner.setSelection(mSortMethod);
        mSortSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                AppPreferences.setCurrentSortMethod(getActivity(), position);
                handleSortSelection(SortOption.getSortMethod(position));
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        Log.d(TAG, "Options menu item selected: " + item.getItemId());
        return true;
    }

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);
        try {
            mListener = (OnMovieSelectedListener) activity;
        } catch (ClassCastException e) {
            throw new ClassCastException(activity.toString() + " must implement OnMovieSelectedListener");
        }
    }

    @Override
    public void onDetach() {
        mListener = null;
        super.onDetach();
    }


    @Override
    public void success(MovieResponse response) {
        mAdapter.setData(response.getMovies());
    }

    @Override
    public void error(ApiError error) {
        if (error.isNetworkError()) {
            Toast.makeText(getActivity(), "Unable to connect to remote host", Toast.LENGTH_LONG).show();
        }
        Log.d(TAG, error.toString());
    }

    public void handleSortSelection(int sortType) {
        if (mSortMethod == sortType)
            return;

        mSortMethod = sortType;

        switch (sortType) {
            case SortOption.POPULARITY:
                mApi.requestMostPopularMovies(this);
                return;
            case SortOption.RATING:
                mApi.requestHighestRatedMovies(this);
                return;
            case SortOption.FAVORITE:
                queryFavorites();
                return;
            default:
                Toast.makeText(getActivity(), "Sort type not supported", Toast.LENGTH_SHORT).show();
                return;
        }
    }

    private void queryFavorites() {
        Log.d(TAG, "Query favorites");
        FavoritesQueryHandler handler = new FavoritesQueryHandler(getActivity().getContentResolver());

        handler.startQuery(1, null, MovieContract.MovieEntry.CONTENT_URI,
              new String[]{"*"},
              MovieContract.MovieEntry.SELECT_FAVORITES,
              null,
              null
        );
    }

    private boolean checkNetwork() {
        if (!isNetworkAvailable()) {
            Toast.makeText(getActivity(), "Network unavailable (check your connection)", Toast.LENGTH_LONG).show();
            return true;
        }
        return false;
    }

    private boolean isNetworkAvailable() {
        ConnectivityManager connectivityManager = (ConnectivityManager) getActivity().getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo activeNetworkInfo = connectivityManager.getActiveNetworkInfo();
        return activeNetworkInfo != null && activeNetworkInfo.isConnectedOrConnecting();
    }
    
    class MovieGridRecyclerAdapter extends RecyclerView.Adapter<MovieGridRecyclerAdapter.MovieGridItemViewHolder> {

        ArrayList<Movie> data = new ArrayList<>();

        public void setData(List<Movie> data) {
            this.data.clear();
            this.data.addAll(data);
            this.notifyDataSetChanged();
            notifyMovieSelectionListener();
        }

        public void notifyMovieSelectionListener() {
            if (mListener != null && !data.isEmpty()) {
                mListener.onMovieSelected(data.get(0), false);
            }
        }

        @Override
        public MovieGridItemViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            LayoutInflater inflater = LayoutInflater.from(parent.getContext());
            View view = inflater.inflate(R.layout.movie_poster_item, parent, false);
            return new MovieGridItemViewHolder(view);
        }

        @Override
        public void onBindViewHolder(MovieGridItemViewHolder holder, int position) {
            Movie movie = data.get(position);
            holder.movieTitle.setText(movie.getTitle());
            int screenWidth = getResources().getDisplayMetrics().widthPixels;
            //Picasso.with(getActivity().getApplicationContext()).setIndicatorsEnabled(true);
            Picasso.with(holder.moviePoster.getContext())
                  .load(movie.getPosterUrl(screenWidth))
                  .placeholder(R.drawable.ic_image_white_36dp)
                  .error(R.drawable.ic_image_white_36dp)
                  .into(holder.moviePoster);
        }

        @Override
        public int getItemCount() {
            return data.size();
        }

        class MovieGridItemViewHolder extends RecyclerView.ViewHolder {

            @Bind(R.id.movie_title)
            TextView movieTitle;

            @Bind(R.id.movie_poster)
            ImageView moviePoster;

            public MovieGridItemViewHolder(View itemView) {
                super(itemView);
                ButterKnife.bind(this, itemView);
            }

            @OnClick(R.id.movie_poster)
            public void onClick() {
                int adapterPosition = this.getAdapterPosition();
                Movie movie = data.get(adapterPosition);
                if (mListener != null) {
                    mListener.onMovieSelected(movie, true);
                }
            }
        }
    }

    class FavoritesQueryHandler extends AsyncQueryHandler {

        public FavoritesQueryHandler(ContentResolver cr) {
            super(cr);
        }

        @Override
        protected void onQueryComplete(int token, Object cookie, Cursor cursor) {
            List<Movie> favorites = new ArrayList<Movie>();

            if (cursor != null && cursor.getCount() > 0) {
                while (cursor.moveToNext()) {
                    Movie movie = Movie.createFromCursor(cursor);
                    favorites.add(movie);
                }
                cursor.close();
            }

            mAdapter.setData(favorites);
        }

    }
}
