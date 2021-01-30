#!/usr/bin/env python

import sys
import json
import socket
import sqlite3


def respond_to_query(response, connection):
	# отправляем ответ, кодируем его в UTF-8
	connection.send(bytes(json.dumps(response), "utf-8"))


def parse_query(query, response):
	# читаем ответ
	try:
		# пытаемся распарсить запрос
		query_dict = json.loads(query)
	except json.JSONDecodeError:
		# если в запросе неправильный json
		# пишем False в success 
		# и сообщение об ошибке
		response["success"] = False
		response["error_msg"] = "Could not parse query"
		return None
	return query_dict


def process_query_and_respond(query, connection):
	response = {"success": True}
	# парсим запрос в python-словарь (ассоциативный массив)
	query_dict = parse_query(query, response)

	if query_dict:
		# открываем соединение с базой данных
		db_connection = sqlite3.connect("data.db")
		db_cursor = db_connection.cursor()

		# в поле type тип запроса: INSERT, SELECT, DELETE
		# в поле table таблица: students, courses, professors (другие захардкожены)
		# payload содержит данные, используемые в запросах
		# success показывает, успешно выполнился запрос или нет
		# если нет, в error_msg записывается ошибка
		# каждый запрос и ответ должен заканчиваться на \n\n
		# (чтобы можно было однозначно понять, что сообщение получено целиком)

		try:
			if query_dict["type"] == "insert":
				if query_dict["table"] == "students":
					db_cursor.execute("INSERT INTO Students VALUES (?,?,?,?,?)", query_dict["payload"])
				elif query_dict["table"] == "courses":
					db_cursor.execute("INSERT INTO ? VALUES (?)")
				elif query_dict["table"] == "professors":
					db_cursor.execute("INSERT INTO Professors VALUES (?,?,?,?,?)", query_dict["payload"])
			elif query_dict["type"] == "select":
				if query_dict["table"] == "students":
					if "payload" in query_dict:
						db_cursor.execute("SELECT * FROM Students WHERE " + query_dict["payload"][0] + "=?", [query_dict["payload"][1]])
					else:
						db_cursor.execute("SELECT * FROM Students")
				elif query_dict["table"] == "professors":
					if "payload" in query_dict:
						db_cursor.execute("SELECT * FROM Professors WHERE " + query_dict["payload"][0] + "=?", query_dict["payload"][1])
					else:
						db_cursor.execute("SELECT * FROM Professors")
				response["payload"] = db_cursor.fetchall()
			elif query_dict["type"] == "delete":
				if query_dict["table"] == "students":
					db_cursor.execute("DELETE FROM Students WHERE " + query_dict["payload"][0] + "=?", query_dict["payload"][1])
				elif query_dict["table"] == "professors":
					db_cursor.execute("DELETE FROM Professors WHERE " + query_dict["payload"][0] + "=?", query_dict["payload"][1])
				elif query_dict["table"] == "courses":
					db_cursor.execute("DELETE FROM Courses WHERE " + query_dict["payload"][0] + "=?", query_dict["payload"][1])
			else:
				response["success"] = False
				response["error_msg"] = "Query type not supported"
		except:
			response["success"] = False
			response["error_msg"] = "Query error"

		# закрываем соединение с базой данных
		db_connection.commit()
		db_connection.close()
	# отправляем ответ на запрос
	respond_to_query(response, connection)


# получаем запрос по кусочкам (1024 байта)
def receive_complete_query(connection):
	query = b""
	while True:
		# если в течение 10 секунд ничего не приходит
		# закрываем соединение
		connection.settimeout(10)
		# читаем 1024 байта
		buf = connection.recv(1024)
		# если что-то получили, добавляем в буфер
		if buf:
			query += buf
			# если получили \n\n - получили конец ответа
			# закрываем соединение
			if query[-2:] == b"\n\n":
				break
		else:
			break
	# функция возвращает то что получила от клиента без последних двух символов (\n\n)
	return query[:-2]


def initialize_tables():
	# инициализация таблиц
	# ничего интересного
	db_connection = sqlite3.connect("data.db")
	db_cursor = db_connection.cursor()

	db_cursor.execute(
		"CREATE TABLE IF NOT EXISTS Departments ("
		"department_id INTEGER PRIMARY KEY,"
		"department_name TEXT NOT NULL)")

	db_cursor.execute(
		"CREATE TABLE IF NOT EXISTS Subjects ("
		"subject_id INTEGER PRIMARY KEY,"
		"subject_name TEXT NOT NULL)")

	db_cursor.execute(
		"CREATE TABLE IF NOT EXISTS Groups ("
		"group_id INTEGER PRIMARY KEY,"
		"group_name TEXT NOT NULL,"
		"department INTEGER NOT NULL,"
		"major INTEGER NOT NULL,"
		"FOREIGN KEY (department) REFERENCES Departments(department_id),"
		"FOREIGN KEY (major) REFERENCES Subjects(subject_id))")

	db_cursor.execute(
		"CREATE TABLE IF NOT EXISTS Students ("
		"student_id INTEGER PRIMARY KEY,"
		"student_name TEXT NOT NULL,"
		"enrollment_date TEXT,"
		"study_group INTEGER NOT NULL,"
		"stipend INTEGER,"
		"FOREIGN KEY (study_group) REFERENCES Groups(group_id))")

	db_cursor.execute(
		"CREATE TABLE IF NOT EXISTS Professors ("
		"professor_id INTEGER PRIMARY KEY,"
		"professor_name TEXT NOT NULL,"
		"department INTEGER NOT NULL,"
		"degree TEXT,"
		"salary INTEGER,"
		"FOREIGN KEY (department) REFERENCES Departments(department_id))")

	db_cursor.execute(
		"CREATE TABLE IF NOT EXISTS Courses ("
		"course_id INTEGER PRIMARY KEY,"
		"course_name TEXT NOT NULL,"
		"subject INTEGER NOT NULL,"
		"term INTEGER NOT NULL,"
		"test TEXT NOT NULL,"
		"hours INTEGER NOT NULL,"
		"FOREIGN KEY (subject) REFERENCES Subjects(subject_id))")

	db_cursor.execute(
		"CREATE TABLE IF NOT EXISTS Grades ("
		"grade_id INTEGER PRIMARY KEY,"
		"course INTEGER NOT NULL,"
		"student INTEGER NOT NULL,"
		"grade INTEGER NOT NULL,"
		"FOREIGN KEY (course) REFERENCES Courses(course_id),"
		"FOREIGN KEY (student) REFERENCES Students(student_id))")

	db_connection.commit()
	db_connection.close()


def main():
	initialize_tables()

	# в Python один "серверный" сокет создаёт "клиентские" сокеты 
	# это серверный сокет
	# SOCK_STREAM означает, что мы ведём потоковую передачу (TCP)
	server_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
	server_socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
	# используем порт 54321
	server_socket.bind(('', 54321))
	# сервер асинхронно слушает запросы
	server_socket.listen(5)

	while True:
		try:
			# принимаем соединение (не асинхронно)
			# connection - это "клиентский" сокет
			connection, _ = server_socket.accept()
			# получаем сообщение целиком
			query = receive_complete_query(connection)
			# обрабатываем
			if query:
				process_query_and_respond(query, connection)
		except socket.timeout:
			pass
		# выход из while True через комбинацию ctrl+c (закрытие программы)
		except KeyboardInterrupt:
			print("Stopping server...")
			break
		# если получили исключение
		# закрываем клиентский сокет
		finally:
			try:
				connection.close()
			except:
				pass
	# закрываем серверный сокет и возвращаем 0
	server_socket.shutdown(socket.SHUT_RDWR)
	server_socket.close()
	return 0


if __name__ == '__main__':
	sys.exit(main())
