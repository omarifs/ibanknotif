<?php
error_reporting(1);
ini_set('display_errors', 1);
ini_set('user_agent','Mozilla/5.0 (X11; Linux x86_64; rv:60.0) Gecko/20100101 Firefox81.0');
//allowed();
/* 
alur pembuatan bot dan webhook
1. chat botfather
2. create bot
3. set command yang diperlukan
4. buat webhook php
5. register webhook pada link https://api.telegram.org/bot5450719124:AAGdJaU_F4PPEd7HmDreQG0wlPQ9nbZ_WCA/setwebhook?url=https://jasaku.web.id/telegram.php. Untuk menghapus webhook ganti setwebhook menjadi deletewebhook
*/
$token='8533838625:AAEeeIpUS8ubYwaVBS0U_Ob0RiHvcptDu80';
define('TOKEN',$token);
define('API_URL',"https://api.telegram.org/bot".TOKEN);
define('JEDA',3);
define('CHATID','-1002031892399');
date_default_timezone_set("Asia/Jakarta");
$db_host='localhost'
;
$db_user='websolusi_telegram';
$db_pass='-39myhGcAn^PLD!n';
$db_name='websolusi_telegram';
$mysqli = new mysqli($db_host, $db_user, $db_pass, $db_name);

if ($mysqli->connect_errno) {
  printf("Database Connect failed: %s\n", $mysqli->connect_error);
  exit();
}

function claim_dedup_key($key, $text) {
  global $mysqli;
  if (!is_string($key) || !preg_match('/^ibanknotif:v1:[a-f0-9]{64}$/', $key)) return false;
  if (!is_string($text) || !preg_match('/^\/(tabin|tabout|pindahin)\s/i', trim($text))) return false;
  $mysqli->query("CREATE TABLE IF NOT EXISTS tele_notif_dedup (dedup_key VARCHAR(80) NOT NULL PRIMARY KEY, C_TIME DATETIME NOT NULL) ENGINE=InnoDB");
  $stmt = $mysqli->prepare("INSERT IGNORE INTO tele_notif_dedup (dedup_key, C_TIME) VALUES (?, NOW())");
  if (!$stmt) return false;
  $stmt->bind_param('s', $key);
  $stmt->execute();
  $claimed = $stmt->affected_rows === 1;
  $stmt->close();
  return $claimed;
}

/**
 * Pastikan tabel cache /pindahin ada dan hapus yang > 15 menit.
 */
function ensure_pindahin_cache() {
  global $mysqli;
  $mysqli->query("CREATE TABLE IF NOT EXISTS tele_pindahin_cache (
    id INT AUTO_INCREMENT PRIMARY KEY,
    src_id VARCHAR(40) NOT NULL,
    dst_id VARCHAR(40) NOT NULL,
    nominal BIGINT NOT NULL,
    created_at DATETIME NOT NULL,
    INDEX idx_src_dst_nom (src_id, dst_id, nominal),
    INDEX idx_created (created_at)
  ) ENGINE=InnoDB");
  // Hapus cache basi (>15 mnt) biar tabel tidak membengkak
  $mysqli->query("DELETE FROM tele_pindahin_cache WHERE created_at < NOW() - INTERVAL 15 MINUTE");
}

/**
 * Simpan satu /pindahin ke cache supaya /tabin dan /tabout
 * dari HP lain bisa di-dedup dalam jendela [CACHE_MENIT] menit.
 * CACHE_MENIT: berapa menit /tabin/tabout ditahan bila ada /pindahin.
 */
define('CACHE_MENIT', 1);

/**
 * Cek apakah id+nominal sudah pernah tercatat sebagai /pindahin
 * dalam CACHE_MENIT menit terakhir, dengan toleransi selisih ±1000.
 */
function covered_by_pindahin($id, $nominal) {
  global $mysqli;
  ensure_pindahin_cache();
  $stmt = $mysqli->prepare(
    "SELECT 1 FROM tele_pindahin_cache
     WHERE (src_id = ? OR dst_id = ?)
       AND ABS(nominal - ?) <= 1000
       AND created_at >= NOW() - INTERVAL ? MINUTE
     LIMIT 1"
  );
  if (!$stmt) return false;
  $menit = CACHE_MENIT;
  $stmt->bind_param('ssdi', $id, $id, $nominal, $menit);
  $stmt->execute();
  $ada = $stmt->get_result()->num_rows > 0;
  $stmt->close();
  return $ada;
}

/**
 * Catat satu /pindahin ke cache supaya covered_by_pindahin()
 * bisa mendeteksinya nanti.
 */
function remember_pindahin($src, $dst, $nominal) {
  global $mysqli;
  ensure_pindahin_cache();
  $stmt = $mysqli->prepare(
    "INSERT INTO tele_pindahin_cache (src_id, dst_id, nominal, created_at) VALUES (?, ?, ?, NOW())"
  );
  if (!$stmt) return;
  $stmt->bind_param('ssi', $src, $dst, $nominal);
  $stmt->execute();
  $stmt->close();
}

$raw_update = file_get_contents('php://input');
if ($raw_update !== false && $raw_update !== '') {
  $json_update = json_decode($raw_update, true);
  $dedup_key = $json_update['message']['dedup_key'] ?? null;
  $dedup_text = $json_update['message']['text'] ?? null;
  if ($dedup_key !== null) {
    if (!claim_dedup_key($dedup_key, $dedup_text)) {
      http_response_code(200);
      header('Content-Type: application/json');
      echo json_encode(['ok' => true, 'deduplicated' => true]);
      exit();
    }
  }
  if (is_array($json_update)) {
    $GLOBALS['ibanknotif_update'] = $json_update;
  }
}

function notif($success=null,$action="add")
{
  switch($action){
    case "add" : $act="Menambah";break;
    case "edit" : $act="Mengubah";break;
    case "save" : $act="Menyimpan";break;
    case "delete" : $act="Menghapus";break;
    default : $act=$action;break;
  }
  if($success==true)
    return "✅ Berhasil $act Data";
  else if($success==false)
    return "❌ Gagal $act Data";
  else
    return "❌ Parameter Salah";
}
function allowed(){
	if($_SERVER['REMOTE_ADDR']=='::1' OR $_SERVER['REMOTE_ADDR']=='103.123.16.133')
	:else:
		header('location:https://tools.websolusi.com');
	endif;
}
function querydb($mysqli, $cmd){
  $res = $mysqli->query($cmd) or die("<h1>Database Error!</h1><p>Error query: <b>$cmd</b> <p>Error message: <i>" . $mysqli->error . "</i>");
  return $res;
}
function CURL($url, $method = 'GET', $data = null, $headers = [], $timeout = 3,$redirect = 5) {
  // Inisialisasi cURL
  $ch = curl_init();

  // Set URL dan metode request
  curl_setopt($ch, CURLOPT_URL, $url);
  curl_setopt($ch, CURLOPT_RETURNTRANSFER, true);
  curl_setopt($ch, CURLOPT_MAXREDIRS, $redirect);
  curl_setopt($ch, CURLOPT_TIMEOUT, $timeout);
  curl_setopt($ch, CURLOPT_CUSTOMREQUEST, strtoupper($method));
  curl_setopt($ch, CURLOPT_USERAGENT, 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/92.0.4515.159 Safari/537.36.'.time());
  curl_setopt($ch, CURLOPT_SSL_VERIFYPEER, false); 
  curl_setopt($ch, CURLOPT_FOLLOWLOCATION, true);

  // Set data untuk POST, PUT, atau DELETE
  if (in_array($method, ['POST', 'PUT', 'DELETE']) && $data) {
      curl_setopt($ch, CURLOPT_POSTFIELDS, $data);
  }

  // Set header tambahan jika ada
  if (!empty($headers)) {
      curl_setopt($ch, CURLOPT_HTTPHEADER, $headers);
  }

  // Eksekusi cURL dan ambil respons
  $response = curl_exec($ch);
  $httpCode = curl_getinfo($ch, CURLINFO_HTTP_CODE);

  // Error handling
  if (curl_errno($ch)) {
      $error = curl_error($ch);
      curl_close($ch);
      return [
          'success' => false,
          'error' => $error,
          'http_code' => null,
          'response' => null
      ];
  }

  // Tutup koneksi cURL
  curl_close($ch);

  // Kembalikan respons
  return [
      'success' => ($httpCode === 200),
      'http_code' => $httpCode,
      'response' => $response
  ];
}
function maxID($table='',$id='id'){
	global $mysqli;
	$sql="SELECT IF($id is not null,MAX($id)+1,1) as id from $table";
	$query=querydb($mysqli,$sql);
	$res=$query->fetch_assoc();
	return $res['id'];
}
function send($chatID,$pesan){
    $data = [
        'chat_id' => $chatID,
        'text' => $pesan,
        'parse_mode' => 'HTML'
    ];
    CURL(API_URL."/sendMessage","POST",$data,$header,30);
}
function sendimage($chatId,$imagePath){
  $header=["Content-Type:multipart/form-data"];
  $data = [
      'chat_id' => $chatId,
      'photo' => new CURLFile($imagePath)
  ];
  CURL(API_URL."/sendPhoto","POST",$data,$header,30);
}
function ganti(array $replace, $subject) { 
	   return str_replace(array_keys($replace), array_values($replace), $subject);    
	}
function getchatinfo(){
	$chatid='-719431992';
	$data= CURL(API_URL."/getChat?chat_id=$chatid");
  return $data["response"];	
}	
function bot(){
     
    $update = $GLOBALS['ibanknotif_update'] ?? json_decode(file_get_contents("php://input"), TRUE);
    $chatID = $update["message"]["chat"]["id"];
    $text=isset($update["message"]["text"]) ? $update["message"]["text"] : false;
	  $date=isset($update["message"]["date"]) ? $update["message"]["date"] : 0;
    if(strstr($text,'/') && $chatID==CHATID){
		global $mysqli;
		$ex=explode(' ',$text);
		$q=querydb($mysqli,'SELECT value as info from tele_konfig where konfig="'.($ex[1] ?? '').'" LIMIT 1');
		$res=$q->fetch_assoc();
		$replace=['.' => ' '];
		if(strstr($text,'/add') && $q->num_rows==0  && !empty($ex[1]) && !empty($ex[2])){
			$sql='INSERT into tele_konfig (id,konfig,value,C_TIME) values ('.maxID('tele_konfig').',"'.$ex[1].'","'.ganti($replace,$ex[2]).'",NOW())';
			querydb($mysqli,$sql);
			if($mysqli->affected_rows>0)
				send($chatID,notif(true,"add"));
			else
				send($chatID,notif(false,"add"));
		}else if(strstr($text,'/edit')  && !empty($ex[1]) && !empty($ex[2])){
			$sql='UPDATE tele_konfig set value="'.ganti($replace,$ex[2]).'" , U_TIME=NOW() where konfig="'.$ex[1].'"';
			querydb($mysqli,$sql);
			if($mysqli->affected_rows>0)
				send($chatID,notif(true,"edit"));
			else
				send($chatID,notif(false,"edit"));
		}else if(strstr($text,'/delete')){
			$sql='DELETE from tele_konfig where konfig="'.$ex[1].'"';
			querydb($mysqli,$sql);
			if($mysqli->affected_rows>0)
				send($chatID,notif(true,"delete"));
			else
				send($chatID,notif(false,"delete"));
		}else if(strstr($text,'/info') && !empty($ex[1])){
      if(!empty($res['info']))
			  send($chatID,$res['info']);
      else
        send($chatID,notif());   
		}	
		else if(strstr($text,'/start')){
			send($chatID,'Selamat datang di bot serbaguna');
		}
		else if(strstr($text,'/help')){
		    $q=querydb($mysqli,'SELECT konfig as info from tele_konfig order by konfig');
		    $message="* /info [perintah] \n Untuk mendapatkan informasi. Berberapa info bisa diakses menggunakan perintah berikut : \n";
		    while($info=$q->fetch_assoc()){
		        $message.="/info ".$info['info']."  \n";    
		    }
		    $message.="\n * <b>/add [perintah] [informasi]</b>. \n Untuk menambahan bot. Gunakan pemisah dengan tanda <b>.</b> jika lebih dari 1 kata  \n\n";
		    $message.="* <b>/edit [perintah] [informasi]</b> \n  Untuk mengubah bot. Gunakan pemisah dengan tanda <b>.</b> jika lebih dari 1 kata  \n\n";
		    $message.="*  <b>/delete [perintah]</b> \n Untuk menghapus bot. \n";
        $message.="*  <b>/tabin [nama_bank.nama_pemilik] [jumlah] [date]</b> \n Untuk menambahkan dana masuk. [date] optional. Date format [YYYY-MM-DD].\n";
        $message.="*  <b>/tabout [nama_bank.nama_pemilik] [jumlah] [date]</b> \n Untuk menambahkan dana keluar. [date] optional. Date format [YYYY-MM-DD].\n";
        $message.="*  <b>/tabtotal</b> \n total tabungan bulan ini. Format : /tabtotal [x bulan terakhir] , /tabtotal on [periode th-bln] \n";
        $message.="*  <b>/tabdel [id atau nomor dari /tabtotal]</b> \n Untuk menghapus salah satu dana keluar atau masuk. \n";
        $message.="*  <b>/pindahin [bank_asal.nama_pemilik] [bank_tujuan.nama_pemilik] [jumlah] [date]</b> \n Untuk menambahkan pindah rekening. [date] optional. Date format [YYYY-MM-DD].\n";
        $message.="*  <b>/emasin [jenis.emas] [jumlah] [harga] [date]</b> \n Untuk menambahkan tabungan emas dibeli. [date] optional. Date format [YYYY-MM-DD].\n";
        $message.="*  <b>/emasout [id atau nomor dari /emastotal] [harga_jual] [date]</b> \n Untuk menambahkan tabungan emas dijual. [date] optional. Date format [YYYY-MM-DD].\n";
        $message.="*  <b>/emastotal</b> \n total tabungan emas. Format : /tabtotal .\n";
        $message.="*  <b>/emasdel [id atau nomor dari /tabtotal]</b> \n Untuk menghapus salah satu data emas. \n";
        $message.="*  <b>/emasharga</b> \n Untuk mengetahui harga emas. \n";
        $message.="*  <b>/reminder [waktu] [text with space]</b> \n Untuk membuat reminder. Tanggal memakai Hari-Bulan (05-10) atau Tahun-Bulan-Hari (2024-07-10).\n";
        $message.="   Sekali : <i>/reminder 2024-07-10 08:00 Pulang Kampung</i> , <i>/reminder 05-10 14:30 Gajian</i>\n";
        $message.="   Berulang tiap selang waktu : <i>/reminder every 2 minute Makan</i> , <i>/reminder every 3 hour Ngopi</i> , <i>/reminder every 1 week Review</i>\n";
        $message.="   Berulang di tanggal tetap : <i>/reminder every month 17 08:00 Bayar</i> , <i>/reminder every 3 month 17 08:00 Bayar</i>\n";
        $message.="   Hari terakhir bulan : <i>/reminder every month last 23:59 Tagihan</i>\n";
        $message.="   Setiap tahun : <i>/reminder every year 17-05 08:00 Ultah</i> , <i>/reminder every 3 year 17-05 08:00 Ultah</i>\n";
        $message.="   Per hari kerja : <i>/reminder every monday 09:00 Standup</i>\n";
        $message.="*  <b>/reminderlist</b> \n Untuk melihat reminder \n";
        $message.="*  <b>/reminderdel [option]</b> \n Untuk menghpaus reminder. option : all , id \n";
        $message.="*  <b>/out [pengeluaran] [jumlah]</b> \n Untuk menghitung biaya  pembangunan rumah. \n";    
        $message.="*  <b>/total</b> \n Untuk melihat total biaya  pembangunan rumah. /total all (untuk melihat detail), /total [th-bln]\n";
        $message.="*  <b>/del [id atau nomor dari /total]</b> \n Untuk menghapus salah satu biaya  pembangunan rumah. \n";
        $message.="*  <b>/cetakinv [order id]</b> \n Untuk cetak invoice shopee. \n";
			send($chatID,$message);
		}else if(strstr($text,'/cetakinv') && !empty($ex[1])){
        $message="Berikut link unduh invoice ".$ex[1].": \n https://shopee.co.id/api/v4/accounting/invoice/download?order_id=".$ex[1];
        send($chatID,$message);
    }
		else if(strstr($text,'/calc')){
		    require_once "Calc.php";
		    $Cal = new calc();
		    $replace=['X'=>'*','x'=>'*',' '=>'','/calc'=>''];
            send($chatID,$Cal->calculate(ganti($replace,$text)));
            
		}else if(strstr($text,'/out')  && !empty($ex[1]) && is_numeric($ex[2])  && is_numeric($ex[3])){
      require_once "Calc.php";
      $Cal = new calc();
			$sql='INSERT into tele_biaya (id,nama,jumlah,harga_satuan,total,C_TIME) values ('.maxID('tele_biaya').',"'.ganti($replace,$ex[1]).'","'.$ex[2].'","'.$ex[3].'","'.($Cal->calculate($ex[2].'*'.$ex[3])).'",NOW())';
			querydb($mysqli,$sql);
			if($mysqli->affected_rows>0)
				send($chatID,notif(true,"add"));
			else
				send($chatID,notif(false,"add"));
		}else if(strstr($text,'/total')){
			$message="Total Biaya pembangunan Rumah adalah sebagai berikut : \n";
      $total_biaya=0;
      if(!empty($ex[1])) {
        if($ex[1]=='all')   
  			  $sql='SELECT * from tele_biaya WHERE MONTH(C_TIME)=MONTH(NOW())';
        else if(!empty($ex[1]))
          $sql='SELECT * from tele_biaya WHERE DATE_FORMAT(C_TIME, "%Y-%m")="'.$ex[1].'"';                 
  			$q=querydb($mysqli,$sql);
        while($total=$q->fetch_assoc()){
  	        $message.=$total['id'].". ".$total['nama']." ".$total['jumlah']." x Rp. ".number_format($total['harga_satuan'],0,",",".")." = Rp. ".number_format($total['total'],0,",",".")."  \n";
            $total_biaya+=$total['total'];   
  	    }
        $message.=" Total ".($ex[1]=='all' ? '1 Bulan' : 'Periode '.$ex[1] )." <b>Rp. ".number_format($total_biaya,0,",",".")."</b> \n";
      }
      $q2=querydb($mysqli,'SELECT SUM(total) as total_all from tele_biaya');
      $all=$q2->fetch_assoc();
      $totalall=$all['total_all'];
      $message.=" Total Keseluruhan <b>Rp. ".number_format($totalall,0,",",".")."</b> \n";
      send($chatID,$message);   
		}else if(strstr($text,'/del')){
			$sql='DELETE from tele_biaya where id="'.$ex[1].'"';
			querydb($mysqli,$sql);
			if($mysqli->affected_rows>0)
				send($chatID,notif(true,"delete"));
			else
				send($chatID,notif(false,"delete"));
		}else if(strstr($text,'/tabin') && !empty($ex[1]) && !empty($ex[2]) && is_numeric($ex[2])){
			$nama_clean = ganti($replace,$ex[1]);
			$total = (int)$ex[2];
			if (covered_by_pindahin($nama_clean, $total)) {
				http_response_code(200);
				header('Content-Type: application/json');
				echo json_encode(['ok'=>true,'deduplicated'=>true,'reason'=>'covered by /pindahin']);
				exit();
			}
			$sql='INSERT into tele_tabungan (nama,total,tipe,C_TIME) values ("'.$nama_clean.'","'.$total.'","in","'.(!empty($ex[3]) ? date("Y-m-d 07:00:00",strtotime($ex[3])) : date("Y-m-d H:i:s") ).'")';
			querydb($mysqli,$sql);
      if($mysqli->affected_rows>0)
				send($chatID,notif(true,"add"));
			else
				send($chatID,notif(false,"add"));
		}else if(strstr($text,'/tabout') && !empty($ex[1]) && !empty($ex[2]) && is_numeric($ex[2])){
			$nama_clean = ganti($replace,$ex[1]);
			$total = (int)$ex[2];
			if (covered_by_pindahin($nama_clean, $total)) {
				http_response_code(200);
				header('Content-Type: application/json');
				echo json_encode(['ok'=>true,'deduplicated'=>true,'reason'=>'covered by /pindahin']);
				exit();
			}
			$sql='INSERT into tele_tabungan (nama,total,tipe,C_TIME) values ("'.$nama_clean.'","'.$total.'","out","'.(!empty($ex[3]) ? date("Y-m-d 07:00:00",strtotime($ex[3])) : date("Y-m-d H:i:s") ).'")';
			querydb($mysqli,$sql);
			if($mysqli->affected_rows>0)
				send($chatID,notif(true,"add"));
			else
				send($chatID,notif(false,"add"));
				}else if(strstr($text,'/pindahin') && !empty($ex[1]) && !empty($ex[2]) && !empty($ex[3]) && is_numeric($ex[3])){
      $pindah_out = (int)$ex[3];
      // Pembulatan ke ribuan hanya sah kalau kedua sisi BERBEDA bank, karena
      // itu kasus Flip: yang keluar dari rekening asal termasuk fee admin, yang
      // sampai di rekening tujuan bersih. Dua rekening di bank yang sama tidak
      // mungkin kena fee, jadi nominalnya wajib sama persis - kalau dibulatkan,
      // uang hilang tanpa jejak. Data lama menunjukkan transfer internal BRI
      // Rp1.234.567 tercatat sebagai pinin 1.234.000, dan ada 4 pinout
      // yang jadi yatim tanpa pasangan pasangannya.
      $beda_bank = substr($ex[1],0,3) !== substr($ex[2],0,3);
      $pindah_in = $beda_bank ? (int)(floor($pindah_out/1000)*1000) : $pindah_out;
      $mysqli->begin_transaction();
      try {
        $sql1='INSERT into tele_tabungan (nama,total,tipe,C_TIME) values ("'.ganti($replace,$ex[1]).'","'.$pindah_out.'","pinout","'.(!empty($ex[4]) ? date("Y-m-d 07:00:00",strtotime($ex[4])) : date("Y-m-d H:i:s") ).'");';
        $mysqli->query($sql1);
        if($mysqli->affected_rows>0){
          $sql2='INSERT into tele_tabungan (nama,total,tipe,C_TIME) values ("'.ganti($replace,$ex[2]).'","'.$pindah_in.'","pinin","'.(!empty($ex[4]) ? date("Y-m-d 07:00:00",strtotime($ex[4])) : date("Y-m-d H:i:s") ).'");';
          $mysqli->query($sql2);
          if($mysqli->affected_rows>0){
            $mysqli->commit();
            remember_pindahin(ganti($replace,$ex[1]), ganti($replace,$ex[2]), $pindah_out);
            send($chatID,notif(true,"add"));
          }else{
            $mysqli->rollback();
            send($chatID,notif(false,"add"));
          }
        }else{
          $mysqli->rollback();
          send($chatID,notif(false,"add"));
        }
      }catch(Exception $e){
        if($mysqli->errno) $mysqli->rollback();
        send($chatID,notif(false,"add"));
      }
		}else if(strstr($text,'/tabtotal')){
      $q2=querydb($mysqli,'SELECT (IF(a.total is null,0,a.total)-IF(b.total is null,0,b.total)) AS total FROM (SELECT SUM(total) AS total FROM tele_tabungan WHERE tipe="in" ) AS a, (SELECT SUM(total) AS total FROM tele_tabungan WHERE tipe="out" ) as b');
      $all=$q2->fetch_assoc();
      $totalall=$all['total'];
      if(is_numeric($ex[1]))
        $sql='SELECT * from tele_tabungan WHERE C_TIME >= LAST_DAY(NOW())+INTERVAL 1 DAY -INTERVAL '.$ex[1].' MONTH ORDER BY C_TIME';
      else if($ex[1]=='on' && !empty($ex[2])){
        $sql='SELECT * from tele_tabungan WHERE DATE_FORMAT(C_TIME, "%Y-%m")="'.$ex[2].'" ORDER BY C_TIME';
        $q2=querydb($mysqli,'SELECT (IF(a.total is null,0,a.total)-IF(b.total is null,0,b.total)) AS total FROM (SELECT SUM(total) AS total FROM tele_tabungan WHERE tipe="in" AND DATE(C_TIME)<DATE("'.$ex[2].'-25")) AS a, (SELECT SUM(total) AS total FROM tele_tabungan WHERE tipe="out" AND DATE(C_TIME)<DATE("'.$ex[2].'-25") ) as b');
        $all=$q2->fetch_assoc();
        $totalall=$all['total'];
      }else {
        if(date('d') >= 25)
          $sql="SELECT * from tele_tabungan WHERE DATE(C_TIME) >= DATE_FORMAT(NOW(), '%Y-%m-25') ORDER BY C_TIME";
        else
          $sql="SELECT * from tele_tabungan WHERE DATE(C_TIME) >= DATE_FORMAT(NOW() - INTERVAL 1 MONTH, '%Y-%m-25') AND DATE(C_TIME) <= DATE_FORMAT(NOW(), '%Y-%m-25') ORDER BY C_TIME;";
      }
			$q=querydb($mysqli,$sql);
			$message="Mutasi Tabungan : \n";
      $total_out=0;$total_in=0;$total_pindahout=0;$total_pindahin=0;
      $rows_out=array();$rows_in=array();
      while($total=$q->fetch_assoc()){
	        $message.=$total['id'].". ".date("Y-m-d",strtotime($total['C_TIME']))." - ".$total['tipe']." - ".$total['nama']." = Rp. ".($total['tipe']=='out' || $total['tipe']=='pinout' ? '-' : '').number_format($total['total'],0,",",".")."  \n";
          if($total['tipe']=='out'){
            $total_out+=$total['total'];
            $rows_out[]=$total;
          } else if($total['tipe']=='in'){
            $total_in+=$total['total'];
            $rows_in[]=$total;
          }
          else if($total['tipe']=='pinout')
           $total_pindahout+=$total['total'];   
          else if($total['tipe']=='pinin')
           $total_pindahin+=$total['total'];             
	    }

      // Transfer antar HP: sisi keluar dan sisi masuk tiba di perangkat BERBEDA,
      // jadi tidak pernah bertemu di aplikasi dan tidak pernah jadi satu
      // /pindahin. Akibatnya barisnya tetap `out` + `in` biasa, sehingga
      // rekap "Pindah Rek" dan "Biaya Adm" di bawah tidak pernah menghitungnya -
      // padahal fee admin benar-benar terpotong.
      //
      // Yang dilakukan HANYA membaca dan mencocokkan. Tidak ada INSERT, UPDATE,
      // atau DELETE di sini: kalau pasangan salah tebak, yang salah hanya angka
      // pada baris tampilan ini, dan saldo tetap dihitung oleh query terpisah.
      // Memindahkan baris ke tipe pinout/pinin justru akan mengubah Mutasi dan
      // Tabungan All Time, dan angka itulah yang dipakai untuk melihat saldo.
      $PAIR_FEE_MAX=1000;
      $pair_lines=array();$pair_nominal=0;$pair_fee=0;$taken_in=array();
      foreach($rows_out as $o){
        $o_amt=(int)$o['total'];
        $o_bank=substr($o['nama'],0,3);
        $o_day=date('Y-m-d',strtotime($o['C_TIME']));
        foreach($rows_in as $k=>$i){
          if(isset($taken_in[$k])) continue;
          if(substr($i['nama'],0,3)==$o_bank) continue;
          if(date('Y-m-d',strtotime($i['C_TIME']))!=$o_day) continue;
          $i_amt=(int)$i['total'];
          if($i_amt>$o_amt) continue;
          if(($o_amt-$i_amt)>$PAIR_FEE_MAX) continue;
          $taken_in[$k]=true;
          $pair_nominal+=$i_amt;
          $pair_fee+=($o_amt-$i_amt);
          $pair_lines[]="  - ".$o['nama']." -> ".$i['nama']." : ".number_format($o_amt,0,",",".")." (biaya ".number_format($o_amt-$i_amt,0,",",".").")";
          break;
        }
      }
      if(count($pair_lines)>0){
        $message.=" Transfer antar HP (terdeteksi dari dua baris, tidak diubah): <b>Rp. ".number_format($pair_nominal,0,",",".")."</b> (Biaya Adm : ".number_format($pair_fee,0,",",".").") \n";
        $message.=implode("\n",$pair_lines)."\n";
      }
      $message.=" Mutasi ".($ex[1] ?? '' )." ".(!empty($ex[2]) ? 'Periode '.$ex[2] : 'Bulan terakhir' )." in ".number_format($total_in,0,",",".")." - out ".number_format($total_out,0,",",".")." = <b>Rp. ".number_format($total_in-$total_out,0,",",".")."</b> \n"; 
      $message.=" Pindah Rek ".($ex[1] ?? '' )." ".(!empty($ex[2]) ? 'Periode '.$ex[2] : 'Bulan terakhir' )." : <b>Rp. ".number_format($total_pindahin,0,",",".")."</b> (Biaya Adm : ".number_format($total_pindahout-$total_pindahin,0,",",".").") \n"; 
      $message.=" Tabungan All Time <b>Rp. ".number_format($totalall,0,",",".")."</b> \n";
      send($chatID,$message);  
		}else if(strstr($text,'/tabdel')){
			$sql='DELETE from tele_tabungan where id="'.$ex[1].'"';
			querydb($mysqli,$sql);
			if($mysqli->affected_rows>0)
				send($chatID,notif(true,"delete"));
			else
				send($chatID,notif(false,"delete"));
		}else if(strstr($text,'/emasin') && !empty($ex[1]) && !empty($ex[2]) && !empty($ex[3]) && is_numeric($ex[2]) && is_numeric($ex[3])){
			$sql='INSERT into tele_tabungan_emas (nama,jumlah,beli,tgl_beli) values ("'.ganti($replace,$ex[1]).'","'.$ex[2].'","'.$ex[3].'","'.(!empty($ex[4]) ? date("Y-m-d 07:00:00",strtotime($ex[4])) : date("Y-m-d H:i:s") ).'")';
			querydb($mysqli,$sql);
      if($mysqli->affected_rows>0)
				send($chatID,notif(true,"add"));
			else
				send($chatID,notif(false,"add"));
		}else if(strstr($text,'/emasout') && !empty($ex[1]) && !empty($ex[2]) && is_numeric($ex[1]) && is_numeric($ex[2])){
			$sql='update tele_tabungan_emas set jual='.$ex[2].', tgl_jual="'.(!empty($ex[3]) ? date("Y-m-d 07:00:00",strtotime($ex[3])) : date("Y-m-d H:i:s") ).'" where id='.$ex[1];
			querydb($mysqli,$sql);
      if($mysqli->affected_rows>0)
				send($chatID,notif(true,"add"));
			else
				send($chatID,notif(false,"add"));
		}else if(strstr($text,'/emas24total')){
      $jsonJual =__DIR__ . '/../scraping-gold/prices.json';
      //$jsonJual = 'scraping-gold/prices.json';
      $jual = json_decode(file_get_contents($jsonJual), true);
      $jual = str_replace(',','', current(array_column(array_filter($jual, fn($item) => $item['type'] === 'sell'), 'price')) ?? 1 );
      $sql="SELECT SUM(jumlah) as total_emas,SUM(jumlah*$jual) as total_harga,SUM(beli) as total_beli, SUM(jumlah*$jual)-SUM(beli) as laba FROM tele_tabungan_emas WHERE nama LIKE 'emas' AND jual IS null";
      $q=querydb($mysqli,$sql);
      $message="Total Tabungan Emas 24 karat: \n";
      if ($row = $q->fetch_assoc()) {
        $message.=" Total Emas Beli <b> {$row['total_emas']} gram</b> \n";
        $message.=" Total Harga Emas Beli <b> Rp. ".number_format($row['total_beli'],0,",",".")."</b> \n";
        $message.=" Total Harga Jual Saat ini <b> Rp. ".number_format($row['total_harga'],0,",",".")."</b> \n";
        $message.=" Perkiraan Laba <b> Rp. ".number_format($row['laba'],0,",",".")."</b> \n"; 
      }else
        $message.="Gagal Menampilkan Data"; 
      send($chatID,$message);

		}else if(strstr($text,'/emastotal')){
      $sql="SELECT * from tele_tabungan_emas ORDER BY tgl_beli;";
			$q=querydb($mysqli,$sql);
			$message="Mutasi Tabungan Emas: \n";
      $total_emas_beli=0;$total_emas_jual=0;$total_in=0;$total_out=0;
      while($total=$q->fetch_assoc()){
	        $message.=$total['id'].". ".$total['tipe']." - ".date("Y-m-d",strtotime($total['tgl_beli']))." - ".$total['nama']." - ".$total['jumlah']." gram = Rp. ".number_format($total['beli'],0,",",".")." (Beli) ".($total['jual']>0 ? number_format($total['jual'],0,",",".")." (Jual ".date("Y-m-d",strtotime($total['tgl_jual'])).")" : "")." \n";
          if($total['jual']>0){
            $total_jual+=$total['jual'];
            $total_emas_jual+=$total['jumlah'];
          }else{
            $total_beli+=$total['beli'];
            $total_emas_beli+=$total['jumlah'];
          }                
	    }
      $message.=" Total Emas Beli <b>".$total_emas_beli." gram</b> \n";
      $message.=" Total Harga Emas Beli <b> Rp. ".number_format($total_beli,0,",",".")."</b> \n";
      $message.=" Total Emas Jual <b>". $total_emas_jual." gram</b> \n";
      $message.=" Total Harga Emas Jual <b> Rp. ".number_format($total_jual,0,",",".")."</b> \n";
      send($chatID,$message);  
		}else if(strstr($text,'/emasdel') && !empty($ex[1])){
			$sql='DELETE from tele_tabungan_emas where id="'.$ex[1].'"';
			querydb($mysqli,$sql);
			if($mysqli->affected_rows>0)
				send($chatID,notif(true,"delete"));
			else
				send($chatID,notif(false,"delete"));
    }else if(strstr($text,'/emasharga')){
			harga_emas_xml($chatID);
    }else if(strstr($text,'/cuaca') && !empty($ex[1])){
			get_cuaca($ex[1],$chatID);
    }else if (strstr($text, "/reminderdel") && !empty($ex[1])) {
      $param = trim(str_replace("/reminderdel", "", $text));
      if ($param === 'all') {
          $sql = "DELETE FROM tele_reminder WHERE chat_id = '$chatID'";
          if (querydb($mysqli, $sql)) {
              send($chatID, notif(true,"Menghapus Seluruh Reminder"));
          } else {
              send($chatID, notif(false,"delete"));
          }
      } elseif (is_numeric($param)) {
          $id = (int)$param;
          $sql = "DELETE FROM tele_reminder WHERE id = $id AND chat_id = '$chatID' LIMIT 1";
          if (querydb($mysqli, $sql) && $mysqli->affected_rows > 0) {
              send($chatID, notif(true,"delete"));
          } else {
              send($chatID, notif(false,"delete"));
          }
      } else 
          send($chatID, "Format salah.\nGunakan:\n- /deletereminder all\n- /reminderdel <id>");
    }else if (strpos($text, "/reminderlist") === 0) {
          $sql = "SELECT * FROM tele_reminder WHERE chat_id = $chatID ORDER BY next_time ASC";
          $result = $mysqli->query($sql);
      
          if ($result && $result->num_rows > 0) {
              $msgOut = "<b>Reminder</b>:\n\n";
              while ($row = $result->fetch_assoc()) {
                  $typeLabel = "once";
      
                  if (($row['mode'] ?? '') === 'repeat') {
                      if (($row['interval_unit'] ?? '') === 'month' && !empty($row['day_of_month'])) {
                          $typeLabel = "every " . max(1, (int)($row['interval_value'] ?? 1)) . " month {$row['day_of_month']} " . (!empty($row['hour_of_day']) ? substr($row['hour_of_day'], 0, 5) : "08:00");
                      } elseif (($row['interval_unit'] ?? '') === 'year' && !empty($row['day_of_month']) && !empty($row['month'])) {
                          $typeLabel = "every " . max(1, (int)($row['interval_value'] ?? 1)) . " year {$row['day_of_month']}/{$row['month']} " . (!empty($row['hour_of_day']) ? substr($row['hour_of_day'], 0, 5) : "08:00");
                      } else {
                          $typeLabel = ($row['interval_unit'] ?? 'repeat') . " x" . ($row['interval_value'] ?? 1);
                      }
                  }
      
                  $msgOut .= "ID\t\t: {$row['id']}\n";
                  $msgOut .= "Type\t: {$typeLabel}\n";
                  $msgOut .= "Waktu\t: {$row['next_time']}\n";
                  $msgOut .= "Reminder\t: {$row['message']}\n\n";
              }
              send($chatID, $msgOut);
          } else {
              send($chatID, "Belum ada reminder.");
          }
    }else if (strpos($text, "/reminder") === 0) {

        $parsed = parse_reminder_command($text);
        if (isset($parsed['error'])) {
            send($chatID, "Gagal menambahkan reminder:\n" . $parsed['error']);
            return;
        }
    
        $id = maxID('tele_reminder');
    
        $chat_id = (int)$chatID;
        $msg = addslashes($parsed['message']);
    
        $mode = $parsed['mode'] ?? 'once';
        $interval_value = (int)($parsed['interval_value'] ?? 1);
    
        $interval_unit = $parsed['interval_unit'] ?? null;
        $weekday = $parsed['weekday'] ?? null;
    
        $hour_of_day = $parsed['hour_of_day'] ?? null;
        $day_of_month = isset($parsed['day_of_month']) ? (int)$parsed['day_of_month'] : null;
        $month = isset($parsed['month']) ? (int)$parsed['month'] : null;
    
        $next_time = $parsed['next_time'];
    
        $sql = "INSERT INTO tele_reminder
                (id, chat_id, message, mode, interval_value, interval_unit, weekday, hour_of_day, day_of_month, month, next_time, base_time)
                VALUES
                ($id, $chat_id, '$msg', '$mode', $interval_value,
                 " . ($interval_unit ? "'$interval_unit'" : "NULL") . ",
                 " . ($weekday ? "'$weekday'" : "NULL") . ",
                 " . ($hour_of_day ? "'$hour_of_day'" : "NULL") . ",
                 " . ($day_of_month ? $day_of_month : "NULL") . ",
                 " . ($month ? $month : "NULL") . ",
                 '$next_time', NOW())";
    
        if (querydb($mysqli, $sql)) {
            send($chatID, notif(true, "add"));
        } else {
            send($chatID, notif(false, "add"));
        }
    }else
				send($chatID,notif());
	}
    
     
}
function harga_emas_xml($chatID){
  $status=true;
  $data = simplexml_load_file("https://harga-emas.org/data.php?v_type=antam") or die($status=false);
  if($status){
    $harga_beli=number_format((float)$data->dataset[0]->set->attributes()['value'],0,",",".");
    $harga_jual=number_format((float)$data->dataset[1]->set->attributes()['value'],0,",",".");
    
    $message = "<b>Harga Emas Antam (".date("d M Y").")</b> \n";
    $message .="Harga Beli \t: Rp. $harga_beli \n";
    $message .="Harga Jual \t: Rp. $harga_jual \n";
    send($chatID,$message);
  }
}
function table_harga_emas(){
  // URL situs
  $url = 'https://harga-emas.org/logam-mulia/?no_cache';

  // Mengambil konten halaman web
  $html = file_get_contents($url);

  // Membuat objek DOMDocument
  $dom = new DOMDocument();
  libxml_use_internal_errors(true); // Mengabaikan kesalahan HTML yang tidak valid
  $dom->loadHTML($html);
  libxml_clear_errors();
  /*
  // Mencari semua tabel di dalam halaman
  $tables = $dom->getElementsByTagName('table');
  // Iterasi melalui tabel
  foreach ($tables as $table) {
      // Memeriksa apakah tabel memiliki atribut class "in_table"
      if ($table->getAttribute('class') == 'in_table') {
          // Menampilkan konten tabel
          echo "<table border='1' style='border: 1px #000 solid;border-collapse: collapse;'>";
          foreach ($table->getElementsByTagName('tr') as $row) {
              echo "<tr>";
              foreach ($row->getElementsByTagName('td') as $cell) {
                if ($cell->getAttribute('colspan') == '15')
                    echo "<td colspan='15'>" . $cell->nodeValue . "</td>";
                else if ($cell->getAttribute('rowspan') == '2')
                    echo "<td rowspan='2'>" . $cell->nodeValue . "</td>";
                else if ($cell->getAttribute('colspan') == '2')
                    echo "<td colspan='2'>" . $cell->nodeValue . "</td>";
                 else
                    echo "<td>" . $cell->nodeValue . "</td>";
              }
              echo "</tr>";
          }
          echo "</table>";
      }
  }
  */
  $tables = $dom->getElementsByTagName('table');
  echo "<style> table.in_table,.in_table tr,.in_table td {border: 1px #000 solid;border-collapse: collapse;font-size:12px;}</style>";
  foreach ($tables as $table) {
  
      $tableHtml = $dom->saveHTML($table);
      // Menampilkan seluruh HTML dari tabel
      echo $tableHtml;
  }
  
}
function graph($param=null){
  global $mysqli;
  session_start();
  if(!isset($_SESSION['user_email']))
   header('Location: login.php');
  // Get the current date
  $currentDate = new DateTime();
  
  // Check if the current day is greater than 25
  if ($currentDate->format('d') >= 25) {
      // Set the starting day of the month to 25th of the current month
      $currentDate->setDate($currentDate->format('Y'), $currentDate->format('m'), 25);
  } else {
      // Set the starting day of the month to 25th of the previous month
      $currentDate->sub(new DateInterval('P1M')); // Subtract one month
      $currentDate->setDate($currentDate->format('Y'), $currentDate->format('m'), 25);
  }
  $labels = [];$data1 = [] ;$data2 = [];
  if(isset($_GET['start_date']) && isset($_GET['end_date']) && $_GET['start_date']!='' && $_GET['end_date']!=''){
    $sql="SELECT tipe,sum(total) as total,C_TIME from tele_tabungan WHERE DATE(C_TIME) BETWEEN '".$_GET['start_date']."' AND '".$_GET['end_date']."' GROUP BY DATE(C_TIME),tipe ORDER BY C_TIME";
    $sql2="SELECT C_TIME from tele_tabungan WHERE DATE(C_TIME) BETWEEN '".$_GET['start_date']."' AND '".$_GET['end_date']."' GROUP BY DATE(C_TIME) ORDER BY C_TIME";
    $q=querydb($mysqli,$sql2);
    // Populate datasets with actual data from the result set
    $i=0;
    while ($row = $q->fetch_assoc()) {
        $labels[$i] = date('M d', strtotime($row['C_TIME']));
        $data1[$labels[$i]]=0;
        $data2[$labels[$i]]=0;
        $i++; // Add one day
    }
    $q2=querydb($mysqli,$sql);
    // Populate datasets with actual data from the result set
    while ($row = $q2->fetch_assoc()) {
        $formattedDate = date('M d', strtotime($row['C_TIME']));
        
        if (array_key_exists($formattedDate, $data1) && $row['tipe'] == 'in') {
            $data1[$formattedDate] = $row['total'];
        } elseif (array_key_exists($formattedDate, $data2) && $row['tipe'] == 'out') {
            $data2[$formattedDate] = $row['total'];
        }
    } 
  }
  else{
    // Generate labels for one month (e.g., from the 25th to the 24th of the next month)
    if(date('d') >= 25)
        $sql="SELECT tipe,sum(total) as total,C_TIME from tele_tabungan WHERE DATE(C_TIME) >= DATE_FORMAT(NOW(), '%Y-%m-25')  GROUP BY DATE(C_TIME),tipe ORDER BY C_TIME";  
      else
        $sql="SELECT tipe,sum(total) as total,C_TIME from tele_tabungan WHERE DATE(C_TIME) >= DATE_FORMAT(NOW() - INTERVAL 1 MONTH, '%Y-%m-25') AND DATE(C_TIME) <= DATE_FORMAT(NOW(), '%Y-%m-25')  GROUP BY DATE(C_TIME),tipe ORDER BY C_TIME";
    $labels = [];$data1 = [] ;$data2 = [];
    for ($i = 0; $i <= 30; $i++) {
        $labels[$i] = $currentDate->format('M d');
        $data1[$labels[$i]]=0;
        $data2[$labels[$i]]=0;
        $currentDate->add(new DateInterval('P1D')); // Add one day
    }   
    $q=querydb($mysqli,$sql);
    // Populate datasets with actual data from the result set
    while ($row = $q->fetch_assoc()) {
        $formattedDate = date('M d', strtotime($row['C_TIME']));
        
        if (array_key_exists($formattedDate, $data1) && $row['tipe'] == 'in') {
            $data1[$formattedDate] = $row['total'];
        } elseif (array_key_exists($formattedDate, $data2) && $row['tipe'] == 'out') {
            $data2[$formattedDate] = $row['total'];
        }
    }
  
  }
  // Generate sample data for two datasets
  //$data1 = [10, 15, 7, 12, 5, 8, 12, 5, 10, 3, 6, 9, 11, 14, 8, 12, 5, 10, 3, 6, 9, 11, 14, 8, 12, 5, 10, 3, 6, 9];
  //$data2 = [8, 12, 5, 10, 3, 6, 9, 11, 14, 8, 12, 5, 10, 3, 6, 9, 11, 14, 8, 12, 5, 10, 3, 6, 9, 11, 14, 8, 12, 5];
  
  // Combine data into an associative array with multiple datasets
  if($param=='pie')
  {
    $data1=array_sum($data1) ?? 0;
    $data2=array_sum($data2) ?? 0;
    $total=$data1+$data2;
    $chartData = [
        'labels' => ['Pemasukan (IN)', 'Pengeluaran (OUT)'],
        'datasets' => [
            [
                //'label' => ['Pemasukan (IN)', 'Pengeluaran (OUT)'],
                'data' => [ $data1, $data2 ],
                'backgroundColor' => ['rgba(75, 192, 192, 0.2)','rgba(255, 99, 132, 0.2)'],
            ],
        ],
    ];
  }else if($param=='emas'){
    /*$sql="SELECT nama,sum(jumlah) as total_emas,sum(beli) as total_harga ,tgl_beli from tele_tabungan_emas WHERE jual IS NULL GROUP BY DATE(tgl_beli) ORDER BY tgl_beli";  
    $q=querydb($mysqli,$sql);
    // Populate datasets with actual data from the result set
    $labelemas=[];$dataemas=[];
    while ($row = $q->fetch_assoc()) {
        $formattedDate=date('d M Y', strtotime($row['tgl_beli']));
        $labelemas[]=$formattedDate;
        $datagram[$formattedDate] = $row['total_emas'];
        $dataemas[$formattedDate] = $row['total_harga'];
    }
    $chartData = [
        'labels' => $labelemas,
        'datasets' => [
            [
                'label' => 'Jumlah Emas (Gram)',
                'data' => $datagram,
                'backgroundColor' => 'rgba(75, 192, 192, 0.2)',
                'borderColor' => 'rgba(75, 192, 192, 1)',
                'borderWidth' => 1,
                 'yAxisID' =>  'y-price',
            ],
            [
              'label' => 'Harga Beli Emas (Rupiah)',
              'data' => $dataemas,
              'backgroundColor' => 'rgba(255, 99, 132, 0.2)',
              'borderColor' => 'rgba(255, 99, 132, 1)',
              'borderWidth' => 1,
              'yAxisID' => 'y-total'
            ],
        ],
      'scales' => [
            'y-price'=> [
                'type' => 'linear',
                'position' => 'left',
                'title' => [
                    'display' => true,
                    'text' => 'Harga Beli Emas (Rupiah)'
                ]
            ],
            'y-total' => [
                'type' => 'linear',
                'position' => 'right',
                'title' => [
                    'display' => true,
                    'text' => 'Total Emas (Gram)'
                ],
                'grid' => [
                    'drawOnChartArea' => false
                ]
            ]
        ]
    ]; */
    $sql="SELECT beli,jual from tele_tabungan_emas";  
    $q=querydb($mysqli,$sql);
    // Populate datasets with actual data from the result set
    while ($row = $q->fetch_assoc()) {
        if(!empty($row['jual']))
          $gramout[]= $row['jual'];
        else
          $gramin[] = $row['beli'];
    }
    $data1=array_sum($gramin) ?? 0;
    $data2=array_sum($gramout) ?? 0;
    $total=$data1+$data2;
    $chartData = [
        'labels' => ['Emas (Beli)', 'Emas (Jual)'],
        'datasets' => [
            [
                //'label' => ['Pemasukan (IN)', 'Pengeluaran (OUT)'],
                'data' => [ $data1, $data2 ],
                'backgroundColor' => ['rgba(75, 192, 192, 0.2)','rgba(255, 99, 132, 0.2)'],
            ],
        ],
    ];
  }else if($param=='emas30'){
    $jsonBeli = __DIR__.'/emas_harga.json';
    $jsonJual = __DIR__.'/emas_jual.json';
    $beli = json_decode(file_get_contents($jsonBeli), true);
    $jual = json_decode(file_get_contents($jsonJual), true);
    if ($beli === null || $jual === null) die("Gagal membaca file JSON.");
    // Mendapatkan data 30 hari terakhir berdasarkan kalender
    $labels = [];
    $beli30 = [];
    $today = new DateTime();
    
    $totdate= date('n')==3 ? 28 : 30;
    $jamSekarang = (int) date('H');
    for ($i = 0; $i < $totdate; $i++) {
        // Lewati hari ini jika jam masih di bawah 9 pagi
        if ($i === 0 && $jamSekarang < 9) {
            $today->modify('-1 day');
            continue;
        }

        $tanggal = $today->format('j'); // Simpan tanggal dalam array
        $bulan = $today->format('M'); // Simpan bulan
        $labels[] = "$tanggal-$bulan";
        $beli30[] = $beli['harga_emas'][$tanggal] ?? null;
        $jual30[] = $jual['harga_emas'][$tanggal] ?? null;

        $today->modify('-1 day');
    }
    $labels=array_reverse($labels);
    $beli30=array_reverse($beli30);
    $jual30=array_reverse($jual30);
    // Format data untuk chart
    $chartData = [
        'labels' => $labels,
        'datasets' => [
            [
                'label' => 'Harga Beli',
                'data' => $beli30,
                'backgroundColor' => 'rgba(255, 99, 132, 0.2)',
                'borderColor' => 'rgba(255, 99, 132, 1)',
                'borderWidth' => 1,
            ],
            [
              'label' => 'Harga Jual',
              'data' => $jual30,
              'backgroundColor' => 'rgba(75, 192, 192, 0.2)',
              'borderColor' => 'rgba(75, 192, 192, 1)',
              'borderWidth' => 1,
          ]
        ],
    ];
  }else {
    $data2 = array_map(function($num) {
        return $num * -1;
    }, $data2);
    $chartData = [
        'labels' => $labels,
        'datasets' => [
            [
                'label' => 'Pemasukan (IN)',
                'data' => $data1,
                'backgroundColor' => 'rgba(75, 192, 192, 0.2)',
                'borderColor' => 'rgba(75, 192, 192, 1)',
                'borderWidth' => 1,
            ],
            [
                'label' => 'Pengeluaran (OUT)',
                'data' => $data2,
                'backgroundColor' => 'rgba(255, 99, 132, 0.2)',
                'borderColor' => 'rgba(255, 99, 132, 1)',
                'borderWidth' => 1,
            ],
        ],
    ];
  }
  
  // Set the content type to JSON
  header('Content-Type: application/json');
  // Output JSON data
  echo json_encode($chartData);
}
function graph_to_png(){
  $data = [
    'options' => json_encode([
        'chart' => ['type' => 'line'],
        'title' => ['text' => 'Harga Emas'],
        'xAxis' => ['categories' => ['1-2', '2-2', '3-2']],
        'series' => [['data' => [1700, 1725, 1750]]],
    ]),
    'type' => 'image/png',
  ];

  $response = CURL('https://export.highcharts.com/',"POST",$data);
  if($response['success'])  
    file_put_contents('chart.png', file_get_contents('https://export.highcharts.com/' . trim($response['response'])));
  else 
    print_r($response);

}
function table($param){
  global $mysqli;
  session_start();
  if(!isset($_SESSION['user_email']))
   header('Location: login.php');
  
  if($param=='emas'){
    $sql="SELECT nama,jumlah,FORMAT(beli,0) as beli,FORMAT(jual,0) as jual,DATE_FORMAT(tgl_beli, '%Y-%m-%d') as tgl_beli,DATE_FORMAT(tgl_jual, '%Y-%m-%d') as tgl_jual,IF(jual>0,FORMAT(jual-beli,0),'') as laba from tele_tabungan_emas ORDER by DATE(tgl_jual) ASC, DATE(tgl_beli) ASC";  
    $q=querydb($mysqli,$sql);
    while ($row = $q->fetch_assoc()) {
      $data[] = $row;
    }
    
    // Set the content type to JSON
    header('Content-Type: application/json');
    // Output JSON data
    echo json_encode($data);
  }
}
function get_Wilayah($namaWilayah) {
  $jsonFile = 'wilayah-accuweather.json';
  $apikey = "ERLY1zvwx7pfsAop94ZlXGfVMDICd38Y";
  $apiUrl = "https://dataservice.accuweather.com/locations/v1/cities/search?apikey=$apikey&q=";
  
  $data = file_exists($jsonFile) ? json_decode(file_get_contents($jsonFile), true) : [];
  $lowerNamaWilayah = strtolower($namaWilayah);
  
  if (!empty($data[$lowerNamaWilayah])) {
      return $data[$lowerNamaWilayah];
  }else{
    
    $apiResponse = file_get_contents($apiUrl . urldecode($namaWilayah));
    $apiData = json_decode($apiResponse, true);
    
    if (!empty($apiData[0]['Key'])) {
        $data[$lowerNamaWilayah] = $apiData[0]['Key'];
        if (file_put_contents($jsonFile, json_encode($data, JSON_PRETTY_PRINT | JSON_UNESCAPED_UNICODE)) === false) {
            throw new Exception("Gagal menyimpan data ke file JSON");
        }
        return $apiData[0]['Key'];
    }
    else 
      return null;
  }
}
function convertToIcon($param)
{
    $icons = [
        "Berawan" => "â˜ï¸",
        "Hujan Ringan" => "ðŸŒ§ï¸",
        "Hujan Petir" => "â›ˆï¸",
        "Cerah" => "â˜€ï¸",
        "Cerah Berawan" => "ðŸŒ¤ï¸",
        "Kabut/Asap" => "ðŸŒ«ï¸",
        "Petir" => "âš¡"
        // Tambahkan sesuai kebutuhan
    ];
    
    foreach ($icons as $key => $icon) {
        if (strpos($param, $key) !== false) {
            return $icon;
        }
    }
    return "â“"; // Jika tidak ada kecocokan
}
function get_cuaca($chatID,$namaWilayah){
  // Contoh penggunaan
  $namaWilayah=strtolower($namaWilayah);
  $wilayahaccu=str_replace(' ','-',$namaWilayah);
  $kode = get_wilayah($namaWilayah);
  $url = "https://www.accuweather.com/id/id/$wilayahaccu/$kode/weather-forecast/$kode";
  //$url ="https://www.accuweather.com/id/search-locations?query=$wilayahaccu";
  //echo $url;
  // Get the HTML content from the URL
  $response = CURL($url);
  //print_r($response);
  $html = "data tidak bisa ditampilkan";
  if($response['success'])
    $html = $response['response'];
  // Initialize DOMDocument and load the HTML
  $dom = new DOMDocument();
  libxml_use_internal_errors(true); // Disable warnings for invalid HTML
  $dom->loadHTML($html);
  libxml_clear_errors();

  // Create DOMXPath to navigate the DOM
  $xpath = new DOMXPath($dom);
  // Extract the temperature
  $temperatureNode = $xpath->query('//div[@class="temp-container"]//div[@class="temp"]');
  $temperature = $temperatureNode->length > 0 ? trim($temperatureNode->item(0)->textContent) : 'Data not found';

  // Extract the weather phrase
  $phraseNode = $xpath->query('//div[@class="cur-con-weather-card__panel"]//span[@class="phrase"]');
  $phrase = $phraseNode->length > 0 ? trim($phraseNode->item(0)->textContent) : 'Data not found';

  // Extract the weather phrase
  $minuteCastNode = $xpath->query('//p[@class="minutecast-banner__phrase"]');
  $minutecast = $minuteCastNode->length > 0 ? trim($minuteCastNode->item(0)->textContent) : $phrase;

  // Extract RealFeel temperature
  $realFeelNode = $xpath->query('//div[@class="temp-container"]//div[@class="real-feel"]');
  $realFeel = $realFeelNode->length > 0 ? trim($realFeelNode->item(0)->textContent) : 'Data not found';

  $namaWilayah = ucwords($namaWilayah);
  // Prepare the message content for Telegram
  $message = "
  <b>Cuaca Saat Ini di $namaWilayah</b> 
  - <b>Kondisi</b>: $minutecast
  - <b>Suhu</b>: $temperature
  - <b>RealFeel</b>: $realFeel
  ";
  echo $message;
  //send($chatID,$message);
}
function gempa(){
  $file = __DIR__."/gempa.txt";
  $url = "https://www.bmkg.go.id/gempabumi?no_cache";
  $status = true;
  $response = CURL($url);
  //print_r($response);
  $html = "data tidak bisa ditampilkan";
  if($response['success'])
    $html = $response['response'];
  else 
    $status = false;
  //$html = file_get_contents($url) or die($status = false);

  if ($status) {
      // Membuat objek DOMDocument
     
      $dom = new DOMDocument();
      libxml_use_internal_errors(true); // Mengabaikan kesalahan HTML yang tidak valid
      $dom->loadHTML($html);
      libxml_clear_errors();

      // Create DOMXPath to navigate the DOM
      $xpath = new DOMXPath($dom);

      $scriptNode = $xpath->query('//script[@id="__NUXT_DATA__"]');
      $script = $scriptNode->length > 0 ? trim($scriptNode->item(0)->textContent) : 'Data not found';

      $info= json_decode($script, true);
      //var_dump($info);

      if (json_last_error() === JSON_ERROR_NONE) {
        $time = $info[13] . ' ' . $info[14] ?? 'error';
        $timeinfo = $info[30] ?? 'error';
        $magnitude = $info[19] ?? 'error';
        $depth = $info[20] ?? 'error';
        $location = $info[21] ?? 'error';
        $potensial = $info[23] ?? 'error';
        $dirasakan = $info[29] ?? 'error';
        $imageUrl = isset($info[28]) ? "https://data.bmkg.go.id/DataMKG/TEWS/" . $info[28] : 'error';

          // Prepare the message content
          $message = "<b>[BMKG] - Info Gempa ($time)</b> \n" .
                    "- Magnitudo\t: $magnitude\n" .
                    "- Kedalaman\t: $depth\n" .
                    "- Lokasi\t: $location\n" .
                    "- Potensi\t: $potensial\n" .
                    "- Dirasakan\t: $dirasakan\n";
          $temp = file_get_contents($file);

          if ($temp != $timeinfo.$dirasakan) {
              send(CHATID, trim($message));
              if ($imageUrl !== 'Data not found') {
                  sendimage(CHATID, $imageUrl);
              }
          }

          file_put_contents($file, trim($timeinfo.$dirasakan));
      } else {
          echo "Failed to parse JSON";
      }

      
  }
}
function is_valid_hhmm($s) {
  if (!preg_match('/^\d{1,2}:\d{2}$/', $s)) return false;
  [$h,$m] = array_map('intval', explode(':',$s));
  return $h>=0 && $h<=23 && $m>=0 && $m<=59;
}

// Tanggal dengan hari dan jam tetap, di-clamp ke hari terakhir bulan kalau
// tanggalnya tidak ada (mis. 31 di Februari). $month boleh > 12.
// Dipakai parser dan cron supaya jadwal yang disimpan dan jadwal yang
// dihitung tidak bisa berbeda.
function month_pinned_date($year, $month, $day, $hh, $mm) {
  $first = (new DateTime('@' . mktime(0, 0, 0, (int)$month, 1, (int)$year)))
    ->setTimezone(new DateTimeZone(date_default_timezone_get()));
  $daysInMonth = (int)$first->format('t');
  if ($day < 1 || $day > $daysInMonth) $day = $daysInMonth;
  return $first->setDate((int)$first->format('Y'), (int)$first->format('n'), $day)
    ->setTime((int)$hh, (int)$mm, 0);
}

function parse_reminder_command($text) {
  $text = trim($text);
  $text = preg_replace('/^\/reminder(\@\w+)?\s*/i', '', $text);
  if ($text === '') return ["error" => "Pesan tidak boleh kosong."];

  $now = new DateTime();
  $parts = preg_split('/\s+/', trim($text));
  $p = array_map('strtolower', $parts);

  $r = [
    "mode" => "once",
    "interval_value" => 1,
    "interval_unit" => null,
    "weekday" => null,
    "day_of_month" => null,
    "month" => null,
    "hour_of_day" => null,
    "message" => "",
    "next_time" => null
  ];

  // ===================== EVERY =====================
  if (($p[0] ?? '') === 'every') {
    $w1 = $p[1] ?? '';

    // 1) every month 17 08:00 Pesan / every month last 23:59 Pesan
    if (in_array($w1, ['month','months'], true)) {
      $tok = strtolower($p[2] ?? '');
      $time = $p[3] ?? '';
      $msgStart = 4;

      if ($tok === 'last') {
        if (!is_valid_hhmm($time)) return ["error"=>"Jam salah. Contoh: /reminder every month last 23:59 Pesan"];
        $msg = trim(implode(' ', array_slice($parts, 4)));
        if ($msg === '') return ["error"=>"Pesan reminder tidak boleh kosong."];
        [$hh, $mm] = array_map('intval', explode(':', $time));
        $base = (clone $now)->modify("first day of next month");
        $y = (int)$base->format('Y');
        $m = (int)$base->format('n');
        $candidate = new DateTime("$y-$m-01 00:00:00");
        $candidate->modify("last day of this month")->setTime($hh, $mm, 0);
        $r["mode"] = "repeat";
        $r["interval_unit"] = "month";
        $r["interval_value"] = 1;
        $r["day_of_month"] = (int)$candidate->format('j');
        $r["hour_of_day"] = $time . ":00";
        $r["message"] = ucfirst($msg);
        $r["next_time"] = $candidate->format("Y-m-d H:i:00");
        return $r;
      }

      $day = $p[2] ?? '';
      $time = $p[3] ?? '';
      $msg = trim(implode(' ', array_slice($parts, 4)));

      if (!ctype_digit($day) || (int)$day < 1 || (int)$day > 31) return ["error"=>"Format salah. Contoh: /reminder every month 17 08:00 Pesan"];
      if (!is_valid_hhmm($time)) return ["error"=>"Jam salah. Contoh: /reminder every month 17 08:00 Pesan"];
      if ($msg === '') return ["error"=>"Pesan reminder tidak boleh kosong."];

      [$hh, $mm] = array_map('intval', explode(':', $time));
      $y = (int)$now->format('Y');
      $m = (int)$now->format('n');

      $candidate = month_pinned_date($y, $m, (int)$day, $hh, $mm);
      if ($candidate <= $now) {
        $candidate = month_pinned_date($y, $m + 1, (int)$day, $hh, $mm);
      }

      $r["mode"] = "repeat";
      $r["interval_unit"] = "month";
      $r["interval_value"] = 1;
      $r["day_of_month"] = (int)$candidate->format('j');
      $r["hour_of_day"] = $time . ":00";
      $r["message"] = ucfirst($msg);
      $r["next_time"] = $candidate->format("Y-m-d H:i:00");
      return $r;
    }

  // 2) every year 17/05 08:00 Pesan
  if (in_array($w1, ['year','years'], true)) {
    $dm = $p[2] ?? '';
    $time = $p[3] ?? '';
    $msg = trim(implode(' ', array_slice($parts, 4)));

    if (!preg_match('/^(\d{1,2})[\/-](\d{1,2})$/', $dm, $m)) return ["error"=>"Format tahunan salah. Contoh: /reminder every year 17-05 08:00 Pesan"];
    if (!is_valid_hhmm($time)) return ["error"=>"Jam salah. Contoh: /reminder every year 17-05 08:00 Pesan"];
    if ($msg==='') return ["error"=>"Pesan reminder tidak boleh kosong."];

    $day = (int)$m[1]; $mon = (int)$m[2];
    if ($mon < 1 || $mon > 12) return ["error"=>"Bulan harus 01-12."];
    [$hh, $mm] = array_map('intval', explode(':', $time));
    $target = month_pinned_date($now->format('Y'), $mon, $day, $hh, $mm);
    if ($target <= $now) {
      $target = month_pinned_date((int)$now->format('Y') + 1, $mon, $day, $hh, $mm);
    }

    $r["mode"]="repeat";
    $r["interval_unit"]="year";
    $r["interval_value"]=1;
    $r["day_of_month"]=$day;
    $r["month"]=$mon;
    $r["hour_of_day"]=$time.":00";
    $r["message"]=ucfirst($msg);
    $r["next_time"]=$target->format("Y-m-d H:i:00");
    return $r;
  }

  // 3) every 17/05 08:00 Pesan (yearly implicit)
  $dm = $w1;
  $time = $p[2] ?? '';
  if (preg_match('/^(\d{1,2})[\/-](\d{1,2})$/', $dm, $m) && is_valid_hhmm($time)) {
    $msg = trim(implode(' ', array_slice($parts, 3)));
    if ($msg==='') return ["error"=>"Pesan reminder tidak boleh kosong."];

    $day=(int)$m[1]; $mon=(int)$m[2];
    if ($mon < 1 || $mon > 12) return ["error"=>"Bulan harus 01-12."];
    [$hh, $mm] = array_map('intval', explode(':', $time));
    $target = month_pinned_date($now->format('Y'), $mon, $day, $hh, $mm);
    if ($target <= $now) {
      $target = month_pinned_date((int)$now->format('Y') + 1, $mon, $day, $hh, $mm);
    }

    $r["mode"]="repeat";
    $r["interval_unit"]="year";
    $r["interval_value"]=1;
    $r["day_of_month"]=$day;
    $r["month"]=$mon;
    $r["hour_of_day"]=$time.":00";
    $r["message"]=ucfirst($msg);
    $r["next_time"]=$target->format("Y-m-d H:i:00");
    return $r;
  }

  // 4) interval: every 3 days Pesan  / every day Pesan / every 3 month 17 08:00 Pesan / every 3 year 17-05 08:00 Pesan
  $interval = 1;
  $unit = $w1;
  $msgStart = 2;

  if (is_numeric($unit)) {
    $interval = (int)$unit;
    $unit = $p[2] ?? '';
    $msgStart = 3;
  }

  $validUnits = ["minute","minutes","hour","hours","day","days","week","weeks","month","months","year","years"];
  $validWeekdays = ["monday","tuesday","wednesday","thursday","friday","saturday","sunday"];

  if (in_array($unit, $validUnits, true)) {
    $unitBase = rtrim($unit,'s');
    // interval + pinned day for month: every 3 month 17 08:00 Pesan
    if ($unitBase === 'month') {
      $dayTok = $p[$msgStart] ?? '';
      $timeTok = $p[$msgStart + 1] ?? '';
      if (ctype_digit($dayTok) && (int)$dayTok >= 1 && (int)$dayTok <= 31) {
        if (!is_valid_hhmm($timeTok)) return ["error"=>"Jam salah. Contoh: /reminder every {$interval} month {$dayTok} 08:00 Pesan"];
        $msg = trim(implode(' ', array_slice($parts, $msgStart + 2)));
        if ($msg === '') return ["error"=>"Pesan reminder tidak boleh kosong."];
        [$hh, $mm] = array_map('intval', explode(':', $timeTok));
        $y = (int)$now->format('Y');
        $m = (int)$now->format('n');
        $candidate = month_pinned_date($y, $m + $interval, (int)$dayTok, $hh, $mm);
        if ($candidate <= $now) {
          $candidate = month_pinned_date($y, $m + 2 * $interval, (int)$dayTok, $hh, $mm);
        }
        $r["mode"] = "repeat";
        $r["interval_unit"] = $unitBase;
        $r["interval_value"] = $interval;
        $r["day_of_month"] = (int)$candidate->format('j');
        $r["hour_of_day"] = $timeTok . ":00";
        $r["message"] = ucfirst($msg);
        $r["next_time"] = $candidate->format("Y-m-d H:i:00");
        return $r;
      }
    }
    // interval + pinned date for year: every 3 year 17-05 08:00 Pesan
    if ($unitBase === 'year') {
      $dmTok = $p[$msgStart] ?? '';
      $timeTok = $p[$msgStart + 1] ?? '';
      if (preg_match('/^(\d{1,2})[\/-](\d{1,2})$/', $dmTok, $mm)) {
        if (!is_valid_hhmm($timeTok)) return ["error"=>"Jam salah. Contoh: /reminder every {$interval} year {$dmTok} 08:00 Pesan"];
        $msg = trim(implode(' ', array_slice($parts, $msgStart + 2)));
        if ($msg === '') return ["error"=>"Pesan reminder tidak boleh kosong."];
        $day = (int)$mm[1]; $mon = (int)$mm[2];
        if ($mon < 1 || $mon > 12) return ["error"=>"Bulan harus 01-12."];
        $target = month_pinned_date($now->format('Y'), $mon, $day, (int)substr($timeTok, 0, 2), (int)substr($timeTok, 3, 2));
        if ($target <= $now) {
          $target = month_pinned_date((int)$now->format('Y') + $interval, $mon, $day, (int)substr($timeTok, 0, 2), (int)substr($timeTok, 3, 2));
        }
        $r["mode"] = "repeat";
        $r["interval_unit"] = 'year';
        $r["interval_value"] = $interval;
        $r["day_of_month"] = $day;
        $r["month"] = $mon;
        $r["hour_of_day"] = $timeTok . ":00";
        $r["message"] = ucfirst($msg);
        $r["next_time"] = $target->format("Y-m-d H:i:00");
        return $r;
      }
    }

    $msg = trim(implode(' ', array_slice($parts, $msgStart)));
    if ($msg==='') return ["error"=>"Pesan reminder tidak boleh kosong."];

    $target = (clone $now)->modify("+$interval $unitBase");

    $r["mode"]="repeat";
    $r["interval_unit"]=$unitBase;
    $r["interval_value"]=$interval;
    $r["message"]=ucfirst($msg);
    $r["next_time"]=$target->format("Y-m-d H:i:00");
    return $r;
  }

    // 5) weekday: every monday 09:00 Pesan / every monday Pesan
    if (in_array($unit, $validWeekdays, true)) {
      $maybeTime = $p[2] ?? '';
      $msgStart = 2;

      if (is_valid_hhmm($maybeTime)) {
        [$hh,$mm] = array_map('intval', explode(':',$maybeTime));
        $target = (clone $now)->modify("next $unit")->setTime($hh,$mm,0);
        $r["hour_of_day"] = $maybeTime.":00";
        $msgStart = 3;
      } else {
        $target = (clone $now)->modify("next $unit")->setTime(8,0,0);
        $r["hour_of_day"] = "08:00:00";
        $msgStart = 2;
      }

      $msg = trim(implode(' ', array_slice($parts, $msgStart)));
      if ($msg==='') return ["error"=>"Pesan reminder tidak boleh kosong."];

      $r["mode"]="repeat";
      $r["interval_unit"]="weekday";
      $r["weekday"]=$unit;
      $r["interval_value"]=1;
      $r["message"]=ucfirst($msg);
      $r["next_time"]=$target->format("Y-m-d H:i:00");
      return $r;
    }

    return ["error"=>"Format tidak dikenali setelah 'every'."];
  }

  // ===================== ONCE =====================
  // /reminder 2026-05-17 08:00 Pesan or 05-10 14:30 Pesan (DD-MM)
  $date = $p[0] ?? '';
  $time = $p[1] ?? '08:00';
  $msgStart = 2;

  if (!is_valid_hhmm($time)) {
    $time = "08:00";
    $msgStart = 1;
  }

  $dt = false;
  if (preg_match('/^(\d{1,2})[\\/-](\d{1,2})$/', $date, $m) && is_valid_hhmm($time)) {
    // D-M tahun berjalan. Tanggal yang tidak ada di kalender (mis. 30-02)
    // ditolak, bukan digeser ke bulan berikutnya seperti createFromFormat.
    $day = (int)$m[1];
    $mon = (int)$m[2];
    $year = (int)$now->format('Y');
    if (!checkdate($mon, $day, $year)) return ["error"=>"Tanggal tidak ada di kalender. Contoh: /reminder 2026-05-17 08:00 Pesan"];
    $dt = mktime((int)substr($time, 0, 2), (int)substr($time, 3, 2), 0, $mon, $day, $year);
  }
  if ($dt === false) {
    $dt = strtotime("$date $time");
  }
  if (!$dt) return ["error"=>"Format tanggal tidak valid. Contoh: /reminder 2026-05-17 08:00 Pesan"];

  $msg = trim(implode(' ', array_slice($parts, $msgStart)));
  if ($msg === '') return ["error"=>"Pesan reminder tidak boleh kosong."];

  $r["mode"] = "once";
  $r["message"] = ucfirst($msg);
  $r["next_time"] = date("Y-m-d H:i:00", $dt);
  return $r;
}
// Hitung next_time berikutnya untuk satu baris reminder repeat.
// Dipisah dari run_reminder_cron() supaya bisa diuji tanpa database.
function reminder_next_time($row, $baseNow = null) {
  $unit = $row['interval_unit'] ?? '';
  $interval = (int)($row['interval_value'] ?? 1);
  if ($interval <= 0) $interval = 1;

  $time = trim((string)($row['hour_of_day'] ?? '08:00:00'));
  if (!preg_match('/^\d{1,2}:\d{2}:\d{2}$/', $time)) $time = "08:00:00";
  $hh = (int)substr($time,0,2);
  $mm = (int)substr($time,3,2);

  $baseNow = $baseNow ?: new DateTime();
  $current = new DateTime($row['next_time']);

  switch ($unit) {
    case 'minute': $current->modify("+{$interval} minutes"); break;
    case 'hour':   $current->modify("+{$interval} hours"); break;
    case 'day':    $current->modify("+{$interval} days"); break;
    case 'week':   $current->modify("+{$interval} weeks"); break;

    case 'month': {
      $day = (int)($row['day_of_month'] ?? 0);
      if ($day > 0) {
        // Penambahan bulan dihitung dari tanggal 1, bukan dari tanggal yang
        // sudah di-clamp. Kalau dihitung dari 30 November, +1 bulan jadi
        // 30 Desember lalu di-clamp ke 31, sehingga bulan yang hilang.
        $anchor = new DateTime($current->format('Y-m-01 00:00:00'));
        do {
          $current = month_pinned_date($anchor->format('Y'), $anchor->format('n'), $day, $hh, $mm);
          $anchor = $anchor->modify("+{$interval} months");
        } while ($current <= $baseNow);
      } else {
        $current->modify("+{$interval} months");
        $current->setTime($hh,$mm,0);
      }
      break;
    }

    case 'year': {
      $day = (int)($row['day_of_month'] ?? 0);
      $mon = (int)($row['month'] ?? 0);
      if ($day > 0 && $mon > 0) {
        $y = (int)$baseNow->format('Y');
        $next = month_pinned_date($y, $mon, $day, $hh, $mm);
        if ($next <= $baseNow) {
          $next = month_pinned_date($y + $interval, $mon, $day, $hh, $mm);
        }
        $current = $next;
      } else {
        $current->modify("+{$interval} years");
        $current->setTime($hh, $mm, 0);
      }
      break;
    }

    case 'weekday': {
      $wd = strtolower((string)($row['weekday'] ?? ''));
      if ($wd) {
        $current->modify("next $wd");
        $current->setTime($hh,$mm,0);
      } else {
        $current->modify("+1 week");
        $current->setTime($hh,$mm,0);
      }
      break;
    }

    default:
      $current->modify("+1 day");
      $current->setTime($hh,$mm,0);
      break;
  }

  return $current->format("Y-m-d H:i:00");
}

function run_reminder_cron() {
  global $mysqli;
  date_default_timezone_set("Asia/Jakarta");

  $now = date("Y-m-d H:i:00");
  $q = $mysqli->query("SELECT * FROM tele_reminder WHERE next_time <= '$now' ORDER BY next_time ASC");
  if (!$q || $q->num_rows == 0) return;

  while ($row = $q->fetch_assoc()) {
    $id = (int)$row['id'];
    $chatID = (int)$row['chat_id'];

    send($chatID, "\u{23F0} Reminder:\n".$row['message']);

    if (($row['mode'] ?? 'once') === 'once') {
      $mysqli->query("DELETE FROM tele_reminder WHERE id=$id");
      continue;
    }

    $next_time = reminder_next_time($row);
    $mysqli->query("UPDATE tele_reminder SET next_time='$next_time' WHERE id=$id");
  }
}
if (isset($_GET['v']) && $_GET['v']=='graph-line' )
  graph();
else if (isset($_GET['v']) && $_GET['v']=='graph-pie' )
  graph('pie');
else if (isset($_GET['v']) && $_GET['v']=='graph-emas' )
  graph('emas');
else if (isset($_GET['v']) && $_GET['v']=='graph-emas30' )
  graph('emas30');
else if (isset($_GET['v']) && $_GET['v']=='graph-png' )
  graph_to_png();
else if (isset($_GET['v']) && $_GET['v']=='table-emas' )
  table('emas');
else if (isset($_GET['v']) && $_GET['v']=='table-harga' )
  table_harga_emas();
else if (isset($_GET['v']) && $_GET['v']=='get-cuaca' )
  get_cuaca(CHATID,($_GET['lokasi'] ?? '')); 
else if (getenv('V')=='gempa' )
  gempa(); 
else if (getenv('V')=='uptime' )
  @uptime();  
else if (getenv('V')=='emas' )
  harga_emas_xml(CHATID);
else if (getenv('V')=='reminder' )
  run_reminder_cron();
else
  bot();
?>
