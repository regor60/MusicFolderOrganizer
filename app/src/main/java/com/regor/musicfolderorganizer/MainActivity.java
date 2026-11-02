package com.regor.musicfolderorganizer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Intent;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.view.View;
import android.widget.*;
import android.graphics.Typeface;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import android.database.Cursor;

public class MainActivity extends Activity {
    private static final int PICK_MUSIC = 101;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private LinearLayout list;
    private TextView status;
    private Button moveButton;
    private Button createFolderButton;
    private Uri root;
    private final ArrayList<Song> songs = new ArrayList<>();
    private final ArrayList<Folder> folders = new ArrayList<>();

    static class Folder {
        String name;
        Uri uri;
        Folder(String name, Uri uri) { this.name=name; this.uri=uri; }
    }
    static class Song {
        String name, artist="";
        Uri uri;
        Folder destination;
        boolean checked=false;
        Song(String n, Uri u) { name=n; uri=u; }
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setTitle("Music Folder Organizer");
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        int p=dp(12); page.setPadding(p,p,p,p);
        TextView heading=new TextView(this);
        heading.setText("Music Folder Organizer");
        heading.setTextSize(22); heading.setTypeface(null,Typeface.BOLD);
        page.addView(heading);
        TextView intro=new TextView(this);
        intro.setText("Choose your master Music folder. Only audio files directly inside it will be considered. Review and check each move first.");
        page.addView(intro);
        Button choose=new Button(this); choose.setText("Choose Music folder");
        page.addView(choose);
        choose.setOnClickListener(v -> {
            Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|
                    Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            startActivityForResult(i,PICK_MUSIC);
        });
        createFolderButton=new Button(this);
        createFolderButton.setText("Create artist folder");
        createFolderButton.setEnabled(false);
        createFolderButton.setOnClickListener(v -> showCreateFolderDialog());
        page.addView(createFolderButton);
        status=new TextView(this); status.setText("No folder selected");
        status.setPadding(0,dp(8),0,dp(8)); page.addView(status);
        moveButton=new Button(this); moveButton.setText("Move checked songs");
        moveButton.setEnabled(false); page.addView(moveButton);
        moveButton.setOnClickListener(v -> confirmMoves());
        ScrollView scroll=new ScrollView(this);
        list=new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        page.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        setContentView(page);
    }

    private int dp(int n) {return (int)(n*getResources().getDisplayMetrics().density+.5f);}

    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if(request!=PICK_MUSIC||result!=RESULT_OK||data==null||data.getData()==null) return;
        root=data.getData();
        try {getContentResolver().takePersistableUriPermission(root,Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);}
        catch(Exception ignored) {}
        rescan();
    }

    private void rescan() {
        if(root==null)return;
        status.setText("Scanning loose files and artist folders…");
        list.removeAllViews();
        moveButton.setEnabled(false);
        createFolderButton.setEnabled(false);
        executor.execute(this::scan);
    }

    private void showCreateFolderDialog() {
        if(root==null)return;
        EditText entry=new EditText(this);
        entry.setSingleLine(true);
        entry.setHint("Artist name");
        LinearLayout wrap=new LinearLayout(this);
        wrap.setPadding(dp(20),0,dp(20),0);
        wrap.addView(entry,new LinearLayout.LayoutParams(-1,-2));
        AlertDialog dialog=new AlertDialog.Builder(this)
                .setTitle("Create artist folder")
                .setMessage("Create a new subfolder inside your selected Music folder. The app will then rescan all loose songs and suggest matching folders.")
                .setView(wrap)
                .setNegativeButton("Cancel",null)
                .setPositiveButton("Create",null)
                .create();
        dialog.setOnShowListener(ignored->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            String name=entry.getText().toString().trim();
            if(name.isEmpty()||name.equals(".")||name.equals("..")||name.contains("/")||name.contains("\\\\")) {
                entry.setError("Enter a valid artist folder name");return;
            }
            for(Folder folder:folders)if(folder.name.equalsIgnoreCase(name)) {
                entry.setError("This folder already exists");return;
            }
            dialog.dismiss();
            status.setText("Creating folder: "+name+"…");
            moveButton.setEnabled(false);
            createFolderButton.setEnabled(false);
            executor.execute(()->{
                String failure=null;
                try {
                    Uri parent=DocumentsContract.buildDocumentUriUsingTree(root,DocumentsContract.getTreeDocumentId(root));
                    Uri created=DocumentsContract.createDocument(getContentResolver(),parent,
                            DocumentsContract.Document.MIME_TYPE_DIR,name);
                    if(created==null)throw new IllegalStateException("Storage provider did not create folder");
                }catch(Exception ex){failure=ex.getMessage();}
                if(failure==null) {
                    main.post(()->status.setText("Folder created. Rescanning Music…"));
                    scan();
                }else{
                    final String error=failure;
                    main.post(()->{
                        createFolderButton.setEnabled(true);
                        updateMoveButton();
                        status.setText("Could not create folder: "+error);
                        new AlertDialog.Builder(this).setTitle("Folder not created")
                                .setMessage(error).setPositiveButton("OK",null).show();
                    });
                }
            });
        }));
        dialog.show();
    }

    private void scan() {
        ArrayList<Song> found=new ArrayList<>();
        ArrayList<Folder> dirs=new ArrayList<>();
        String error=null;
        try {
            ContentResolver resolver=getContentResolver();
            String rootId=DocumentsContract.getTreeDocumentId(root);
            Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(root,rootId);
            String[] columns={DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE};
            try(Cursor c=resolver.query(children,columns,null,null,null)) {
                if(c==null) throw new IllegalStateException("Cannot read this folder");
                while(c.moveToNext()) {
                    String id=c.getString(0), name=c.getString(1), mime=c.getString(2);
                    if(id==null||name==null)continue;
                    Uri child=DocumentsContract.buildDocumentUriUsingTree(root,id);
                    if(DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) dirs.add(new Folder(name,child));
                    else if(isAudio(name,mime)) found.add(new Song(name,child));
                }
            }
            dirs.sort(Comparator.comparing(f->f.name.toLowerCase(Locale.ROOT)));
            for(Song s:found) {
                s.artist=readArtist(s.uri);
                s.destination=findMatch(s,dirs);
            }
            found.sort(Comparator.comparing(s->s.name.toLowerCase(Locale.ROOT)));
        } catch(Exception e) {error=e.getMessage();}
        final String issue=error;
        main.post(()-> {
            songs.clear(); songs.addAll(found); folders.clear(); folders.addAll(dirs);
            createFolderButton.setEnabled(root!=null);
            if(issue!=null) {
                status.setText("Scan failed: "+issue);
                return;
            }
            int proposed=0; for(Song s:songs) if(s.destination!=null) proposed++;
            status.setText(songs.size()+" loose audio files; "+folders.size()+" artist folders; "+proposed+" suggested destinations. Tap any destination to change it.");
            drawRows();
        });
    }

    private boolean isAudio(String name,String mime) {
        String n=name.toLowerCase(Locale.ROOT);
        return (mime!=null&&mime.startsWith("audio/"))||
            n.matches(".*\\.(mp3|flac|m4a|aac|ogg|opus|wav|wma|aiff|alac)$");
    }
    private String readArtist(Uri uri) {
        MediaMetadataRetriever m=new MediaMetadataRetriever();
        try {
            m.setDataSource(this,uri);
            String artist=m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST);
            return artist==null?"":artist.trim();
        } catch(Exception e) {return "";}
        finally {try{m.release();}catch(Exception ignored){}}
    }
    private String norm(String s) {
        return s.toLowerCase(Locale.ROOT).replace("&","and").replaceAll("[^a-z0-9]","");
    }
    private Folder findMatch(Song s,List<Folder> dirs) {
        String filename=s.name.replaceFirst("(?i)\\.(mp3|flac|m4a|aac|ogg|opus|wav|wma|aiff|alac)$","");
        filename=filename.replaceFirst("^\\s*\\d{1,3}[\\s._-]+","");
        String nf=norm(filename), na=norm(s.artist);
        Folder best=null; int score=0; boolean tie=false;
        for(Folder f:dirs) {
            String name=norm(f.name);
            if(name.length()<3)continue;
            int candidate=0;
            if(!na.isEmpty()&&(na.equals(name)||na.startsWith(name+"and")))candidate=1000+name.length();
            else if(nf.contains(name))candidate=100+name.length();
            if(candidate>score) {score=candidate;best=f;tie=false;}
            else if(candidate>0&&candidate==score)tie=true;
        }
        return tie?null:best;
    }

    private void drawRows() {
        list.removeAllViews();
        if(songs.isEmpty()) {
            TextView empty=new TextView(this);
            empty.setText("No loose audio files found in this folder.");
            list.addView(empty); moveButton.setEnabled(false);return;
        }
        for(Song s:songs) {
            LinearLayout row=new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL); row.setPadding(dp(4),dp(8),dp(4),dp(8));
            CheckBox cb=new CheckBox(this);
            cb.setText(s.name);
            cb.setChecked(s.checked); cb.setEnabled(s.destination!=null);
            cb.setOnCheckedChangeListener((button,checked)->{s.checked=checked;updateMoveButton();});
            row.addView(cb);
            TextView meta=new TextView(this);
            meta.setText("Artist tag: "+(s.artist.isEmpty()?"(missing)":s.artist));
            meta.setTextSize(12); row.addView(meta);
            Button target=new Button(this);
            target.setAllCaps(false);
            target.setText(s.destination==null?"No match — choose folder":
                    "Destination: "+s.destination.name+"  (change)");
            target.setOnClickListener(v -> chooseDestination(s));
            row.addView(target);
            View line=new View(this);line.setBackgroundColor(0xffdddddd);
            list.addView(row);
            list.addView(line,new LinearLayout.LayoutParams(-1,dp(1)));
        }
        updateMoveButton();
    }

    private void chooseDestination(Song s) {
        String[] names=new String[folders.size()+2];
        names[0]="No destination";
        for(int i=0;i<folders.size();i++)names[i+1]=folders.get(i).name;
        names[names.length-1]="+ Create new artist folder…";
        new AlertDialog.Builder(this).setTitle("Destination for "+s.name)
            .setItems(names,(d,which)->{
                if(which==names.length-1){showCreateFolderDialog();return;}
                s.destination=which==0?null:folders.get(which-1);
                s.checked=false;drawRows();
            }).show();
    }
    private void updateMoveButton() {
        int count=0;for(Song s:songs)if(s.checked&&s.destination!=null)count++;
        moveButton.setText("Move "+count+" checked song"+(count==1?"":"s"));
        moveButton.setEnabled(count>0);
    }
    private void confirmMoves() {
        ArrayList<Song> selected=new ArrayList<>();
        StringBuilder names=new StringBuilder();
        for(Song s:songs) if(s.checked&&s.destination!=null) {
            selected.add(s);
            if(selected.size()<=8) names.append(s.name).append(" → ").append(s.destination.name).append("\n");
        }
        if(selected.isEmpty())return;
        if(selected.size()>8)names.append("…and ").append(selected.size()-8).append(" more");
        new AlertDialog.Builder(this).setTitle("Move "+selected.size()+" file(s)?")
                .setMessage(names.toString()+"\nThe original files will be moved, not copied.")
                .setNegativeButton("Cancel",null)
                .setPositiveButton("Move",(d,w)->performMoves(selected)).show();
    }
    private void performMoves(ArrayList<Song> selected) {
        moveButton.setEnabled(false);
        status.setText("Moving "+selected.size()+" files…");
        executor.execute(()->{
            ArrayList<Song> moved=new ArrayList<>();
            ArrayList<String> failures=new ArrayList<>();
            String rootId=DocumentsContract.getTreeDocumentId(root);
            Uri parent=DocumentsContract.buildDocumentUriUsingTree(root,rootId);
            for(Song s:selected) {
                try {
                    Uri output=DocumentsContract.moveDocument(getContentResolver(),s.uri,parent,s.destination.uri);
                    if(output==null)failures.add(s.name+": provider did not move file");
                    else moved.add(s);
                } catch(Exception e) {failures.add(s.name+": "+e.getClass().getSimpleName());}
            }
            main.post(()->{
                songs.removeAll(moved);
                for(Song s:songs)s.checked=false;
                status.setText("Moved "+moved.size()+" of "+selected.size()+" files. "+
                        (failures.isEmpty()?"No errors.":failures.size()+" failed; originals retained."));
                drawRows();
                if(!failures.isEmpty())new AlertDialog.Builder(this).setTitle("Move failures")
                        .setMessage(android.text.TextUtils.join("\n",failures)).setPositiveButton("OK",null).show();
            });
        });
    }

    @Override protected void onDestroy() {super.onDestroy();executor.shutdownNow();}
}
