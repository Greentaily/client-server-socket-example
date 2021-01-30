package com.example.forms;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Bundle;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.gson.Gson;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TableActivity extends AppCompatActivity {

    private String host;
    private int port;
    private String table;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_table);

        // получаем параметры из ConnectionSettingsActivity
        Intent intent = getIntent();
        host = intent.getStringExtra("address");
        port = intent.getIntExtra("port", 0);
        // задаём таблицу students и сразу обновляем данные
        table = "students";
        updateGrid();

        // тулбар
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        // кнопка добавления полей
        // на данный момент задействуется для переключения таблиц
        FloatingActionButton fab = findViewById(R.id.fab);
        fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                table = (table.equals("professors") ? "students" : "professors");
                updateGrid();
            }
        });
    }

    // диалог нажатия кнопки назад
    @Override
    public void onBackPressed() {
        new AlertDialog.Builder(this)
                .setTitle("Выйти из таблиц")
                .setMessage("Вернуться на экран подключения?")
                .setPositiveButton("Да", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int id) {
                        quit();
                    }
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void quit() {
        super.onBackPressed();
    }

    // обновляем текущую таблицу (отправляем серверу SELECT)
    private void updateGrid() {

        Map<String, Object> query = new HashMap<>();
        query.put("type", "select");
        query.put("table", table);
        send(query);
    }

    private void send(Map<String, Object> queryData) {
        // библиотека json от google
        Gson gson = new Gson();
        // создаём строку-запрос из объекта map
        String query = gson.toJson(queryData) + "\n\n";
        // создаём асинхронную задачу (в другом потоке) для работы с сервером
        new TableActivity.SendAsyncTask(this).execute(host, String.valueOf(port), query);
    }

    private static class SendAsyncTask extends AsyncTask<String, Void, String> {
        // благодаря WeakReference
        // задача не создаст утечку памяти если activity уничтожится до того, как задача завершится
        private final WeakReference<TableActivity> activity;
        private final AlertDialog dialog;
        private Exception exception;

        SendAsyncTask(TableActivity activity) {
            super();
            this.activity = new WeakReference<>(activity);
            // создаём диалог получения данных, покажем его позже
            dialog = new AlertDialog.Builder(this.activity.get())
                    .setMessage("Получение данных...")
                    .setCancelable(false)
                    .setNegativeButton("Отмена", new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialogInterface, int i) {
                            cancel(true);
                        }
                    }).create();
        }

        @Override
        protected void onPreExecute() {
            super.onPreExecute();
            // показываем диалог!
            dialog.show();
        }

        @Override
        protected String doInBackground(String... connectionParameters) {
            // строим ответ, получаемый от сервера (шаблон проектирования builder!)
            StringBuilder response = new StringBuilder();
            try {
                // открываем сокет, connectionParameters[0] - адрес, connectionParameters[1] - порт
                Socket socket = new Socket(connectionParameters[0], Integer.parseInt(connectionParameters[1]));
                OutputStream out = socket.getOutputStream();
                // пишем запрос (в connectionParameters[2]) в сокет
                out.write(connectionParameters[2].getBytes(StandardCharsets.US_ASCII));

                InputStream in = socket.getInputStream();
                // читаем ответ кусочками по 256 байт
                // ведь у нас потоковый протокол TCP
                // и вместо дейтаграмм поток байтов
                byte[] receivedDataBuf = new byte[256];
                int bytesReceived = 0;
                // если пришло -1 байтов, значит, сервер закрыл соединение
                // (если пришло 0 байтов, то просто пока что ничего не пришло)
                while ((bytesReceived = in.read(receivedDataBuf)) != -1) {
                    // добавляем то что получили в строителя строк
                    response.append(new String(receivedDataBuf, 0, bytesReceived));
                }
                // закрываемся
                socket.close();
                out.close();
                in.close();
            } catch (Exception exception) {
                // если ловим исключение, записываем его, чтобы потом обработать в другом потоке
                this.exception = exception;
                return exception.getMessage();
            }
            return response.toString();
        }

        @Override
        protected void onPostExecute(String response) {
            super.onPostExecute(response);
            // activity завершилась раньше чем задача, больше здесь делать нечего
            if (activity.get() == null) return;
            // мы поймали исключение в потоке задачи, теперь можно обработать его в потоке UI
            else if (exception != null) {
                Toast.makeText(activity.get(), exception.getMessage(), Toast.LENGTH_LONG).show();
            }
            else {
                // gson - библиотека работы с json от google
                Gson gson = new Gson();
                // создаём map из ответа от сервера
                Map map = gson.fromJson(response, Map.class);
                // в этом поле сервер пишет, успешно выполнен запрос или нет
                if ((Boolean) (map.get("success"))) {
                    // если в ответе есть какие-то данные (например, запрос был SELECT)
                    // обработаем их
                    if (map.containsKey("payload")) {
                        LinearLayout tableView = activity.get().findViewById(R.id.tableView);

                        int size = ((ArrayList<Object>) map.get("payload")).size();

                        // очищаем таблицу
                        activity.get().clearGrid();
                        // создаём заголовки таблицы
                        activity.get().createTableTitle(tableView);
                        for (int i = 0; i < size; i++) {
                            // добавляем поле в таблицу
                            activity.get().addRow(tableView, (List<Object>) ((ArrayList<Object>) map.get("payload")).get(i));
                        }
                    } else {
                        // в ответе нет никаких данных (например, запрос был DELETE)
                        Toast.makeText(activity.get(), "Успешно: " + map.get("success").toString(), Toast.LENGTH_LONG).show();
                    }
                } else {
                    // в ответе нет даже поля success
                    // сервер передал некорректный ответ
                    Toast.makeText(activity.get(), "Нет ответа от сервера.", Toast.LENGTH_LONG).show();
                }
            }
            // закрываем диалог
            dialog.dismiss();
        }
    }

    private void createTableTitle(LinearLayout table) {
        // создаём поле
        LinearLayout row = new LinearLayout(this);
        // параметры отображения содержимого
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 7);
        row.setLayoutParams(params);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setWeightSum(100);

        // содержимое заголовков таблицы
        // (остальные данные из БД не должны выводиться в таблице
        // для них должен быть диалог "подробнее" или типа того...)
        String[] title;

        switch (this.table) {
            case "students":
                title = new String[]{"id", "Имя", "Зачисление", "Группа", "Стипендия"};
                break;
            case "professors":
                title = new String[]{"id", "Имя", "Кафедра", "Степень", "Зарплата"};
                break;
            default:
                title = new String[]{"ERROR"};
                break;
        }

        for (int i = 0; i < title.length; i++) {
            // в первой колонке всегда id
            // её можно ужать, чтобы сэкономить экранное место
            // а то на экране телефона нифига не помещается
            float weight = (i == 0 ? 10f : 23f);
            LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight);
            TextView column = new TextView(this);
            column.setText(title[i]);
            column.setTextSize(13);
            column.setPadding(0, 0, 0, 0);
            column.setLayoutParams(textParams);
            column.setTextAlignment(View.TEXT_ALIGNMENT_TEXT_START);
            row.addView(column);
        }
        table.addView(row);
    }

    private void addRow(LinearLayout table, List<Object> data) {
        // всё как в createTableTitle
        // но данные берутся из payload из ответа сервера (data)
        // немного подгоняем отображение, чтобы отличалось от заголовков
        LinearLayout row = new LinearLayout(this);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, 7);
        row.setLayoutParams(params);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setWeightSum(100);
        for (int i = 0; i < data.size(); i++) {
            float weight = (i == 0 ? 10f : 23f);
            LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, weight);
            TextView column = new TextView(this);
            column.setText(data.get(i).toString());
            column.setTextSize(10);
            column.setPadding(0,0,0,0);
            column.setLayoutParams(textParams);
            column.setTextAlignment(View.TEXT_ALIGNMENT_TEXT_START);
            row.addView(column);
        }
        table.addView(row);
    }

    private void clearGrid() {
        LinearLayout layout = findViewById(R.id.tableView);
        layout.removeAllViewsInLayout();
    }
}