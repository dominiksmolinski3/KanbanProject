PGDMP  6    :                }            kanban    17.2    17.2 
    �           0    0    ENCODING    ENCODING        SET client_encoding = 'UTF8';
                           false            �           0    0 
   STDSTRINGS 
   STDSTRINGS     (   SET standard_conforming_strings = 'on';
                           false            �           0    0 
   SEARCHPATH 
   SEARCHPATH     8   SELECT pg_catalog.set_config('search_path', '', false);
                           false            �           1262    57575    kanban    DATABASE     y   CREATE DATABASE kanban WITH TEMPLATE = template0 ENCODING = 'UTF8' LOCALE_PROVIDER = libc LOCALE = 'Polish_Poland.1250';
    DROP DATABASE kanban;
                     postgres    false            �          0    57588    columns 
   TABLE DATA           6   COPY public.columns (id, name, wip_limit) FROM stdin;
    public               postgres    false    220   �       �          0    57608    task 
   TABLE DATA           4   COPY public.task (id, title, column_id) FROM stdin;
    public               postgres    false    222   N       �          0    57577    users 
   TABLE DATA           :   COPY public.users (id, email, password, name) FROM stdin;
    public               postgres    false    218   �       �           0    0    columns_id_seq    SEQUENCE SET     <   SELECT pg_catalog.setval('public.columns_id_seq', 9, true);
          public               postgres    false    219            �           0    0    task_id_seq    SEQUENCE SET     9   SELECT pg_catalog.setval('public.task_id_seq', 7, true);
          public               postgres    false    221            �           0    0    users_id_seq    SEQUENCE SET     :   SELECT pg_catalog.setval('public.users_id_seq', 1, true);
          public               postgres    false    217            �   ?   x�3�J-,M-.IMᴴ��t��K�,9]+
RS2SK�\sN�<��������bN#�=... t�      �   J   x�3�*�O�LV((��J�.���2�"f�.f�eƙR�X���i�eΙ�R�MAB@1��	gjqq"�W� ���      �      x�3�THI,"N8�������� |!      