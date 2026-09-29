use strict;
use warnings;
use Test::More tests => 7;
use File::Temp qw(tempfile);

sub run_and_capture {
    my ($switch) = @_;
    my ($out_fh, $out_file) = tempfile();
    my ($err_fh, $err_file) = tempfile();
    close $out_fh;
    close $err_fh;

    my $status = system(
        "$^X $switch -e 'print chr(256)' > \"$out_file\" 2> \"$err_file\""
    );
    open my $read_out, '<:raw', $out_file or die "open $out_file: $!";
    local $/;
    my $output = <$read_out>;
    close $read_out;
    open my $read_err, '<:raw', $err_file or die "open $err_file: $!";
    my $error = <$read_err>;
    close $read_err;
    unlink $out_file, $err_file;
    return ($status, $output, $error);
}

my ($status, $output, $error) = run_and_capture('-CO');
is($status, 0, '-CO child exits successfully');
is($output, "\xC4\x80", '-CO writes UTF-8 output');
is($error, '', '-CO suppresses wide-character warning');

($status, $output, $error) = run_and_capture('-C2');
is($output, "\xC4\x80", '-C2 enables UTF-8 stdout');

my ($input_fh, $input_file) = tempfile();
binmode $input_fh, ':raw';
print {$input_fh} "\xC4\x80";
close $input_fh;
my ($ord_fh, $ord_file) = tempfile();
close $ord_fh;
my $input_status = system(
    "$^X -CI -e 'print ord(<STDIN>)' < \"$input_file\" > \"$ord_file\""
);
open my $read_ord, '<', $ord_file or die "open $ord_file: $!";
my $ord_output = <$read_ord>;
close $read_ord;
is($input_status, 0, '-CI child exits successfully');
is($ord_output, '256', '-CI decodes UTF-8 standard input');

my ($data_fh, $data_file) = tempfile();
binmode $data_fh, ':raw';
print {$data_fh} "\xC4\x80";
close $data_fh;
my ($script_fh, $script_file) = tempfile();
print {$script_fh} "open(F, q(<$data_file)); print ord(<F>); close F";
close $script_fh;
my $scope_output = qx{$^X -Ci -e 'do q($script_file)'};
is($scope_output, '196', '-Ci open layer is scoped to the current file');
unlink $input_file, $ord_file, $data_file, $script_file;
