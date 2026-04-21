package Image::Magick;

use strict;
use warnings;

our $VERSION = '7.1.2';
our @ISA     = qw(Exporter);
our @EXPORT  = qw(
    Success Transparent Opaque QuantumDepth QuantumRange MaxRGB
    WarningException ResourceLimitWarning TypeWarning OptionWarning
    DelegateWarning MissingDelegateWarning CorruptImageWarning
    FileOpenWarning BlobWarning StreamWarning CacheWarning CoderWarning
    ModuleWarning DrawWarning ImageWarning XServerWarning RegistryWarning
    ConfigureWarning ErrorException ResourceLimitError TypeError
    OptionError DelegateError MissingDelegateError CorruptImageError
    FileOpenError BlobError StreamError CacheError CoderError
    ModuleError DrawError ImageError XServerError RegistryError
    ConfigureError FatalErrorException
);

require Exporter;

# --- Constants (match PerlMagick severity codes) ---

use constant Success                  => 0;
use constant WarningException         => 300;
use constant ResourceLimitWarning     => 300;
use constant TypeWarning              => 305;
use constant OptionWarning            => 310;
use constant DelegateWarning          => 315;
use constant MissingDelegateWarning   => 320;
use constant CorruptImageWarning      => 325;
use constant FileOpenWarning          => 330;
use constant BlobWarning              => 335;
use constant StreamWarning            => 340;
use constant CacheWarning             => 345;
use constant CoderWarning             => 350;
use constant ModuleWarning            => 355;
use constant DrawWarning              => 360;
use constant ImageWarning             => 365;
use constant XServerWarning           => 370;
use constant RegistryWarning          => 375;
use constant ConfigureWarning         => 380;
use constant ErrorException           => 400;
use constant ResourceLimitError       => 400;
use constant TypeError                => 405;
use constant OptionError              => 410;
use constant DelegateError            => 415;
use constant MissingDelegateError     => 420;
use constant CorruptImageError        => 425;
use constant FileOpenError            => 430;
use constant BlobError                => 435;
use constant StreamError              => 440;
use constant CacheError               => 445;
use constant CoderError               => 450;
use constant ModuleError              => 455;
use constant DrawError                => 460;
use constant ImageError               => 465;
use constant XServerError             => 470;
use constant RegistryError            => 475;
use constant ConfigureError           => 480;
use constant FatalErrorException      => 700;

use constant Transparent              => 0;
use constant Opaque                   => 65535;
use constant QuantumDepth             => 16;
use constant QuantumRange             => 65535;
use constant MaxRGB                   => 65535;

# --- CLI detection (runs once at load time) ---

my $MAGICK_CMD;     # e.g. 'magick' or undef
my $CONVERT_CMD;    # e.g. 'magick' (IM7) or 'convert' (IM6)
my $IDENTIFY_CMD;   # e.g. 'magick identify' or 'identify'
my $COMPOSITE_CMD;  # e.g. 'magick composite' or 'composite'
my $MONTAGE_CMD;    # e.g. 'magick montage' or 'montage'
my $IM_VERSION;     # 6 or 7

sub _detect_cli {
    return if defined $IM_VERSION;   # already detected

    # Try IM7 first
    for my $cmd (qw(magick magick.exe)) {
        my $out = `$cmd --version 2>&1`;
        if ($? == 0 && $out =~ /ImageMagick\s+(\d+)/) {
            $IM_VERSION    = $1;
            $MAGICK_CMD    = $cmd;
            $CONVERT_CMD   = $cmd;
            $IDENTIFY_CMD  = "$cmd identify";
            $COMPOSITE_CMD = "$cmd composite";
            $MONTAGE_CMD   = "$cmd montage";
            return;
        }
    }

    # Try IM6 (but not on Windows where 'convert' is a system tool)
    if ($^O ne 'MSWin32') {
        for my $cmd (qw(convert)) {
            my $out = `$cmd --version 2>&1`;
            if ($? == 0 && $out =~ /ImageMagick\s+(\d+)/) {
                $IM_VERSION    = $1;
                $CONVERT_CMD   = 'convert';
                $IDENTIFY_CMD  = 'identify';
                $COMPOSITE_CMD = 'composite';
                $MONTAGE_CMD   = 'montage';
                return;
            }
        }
    }

    die "Image::Magick requires ImageMagick CLI tools.\n"
      . "Install with:\n"
      . "  macOS:   brew install imagemagick\n"
      . "  Ubuntu:  sudo apt install imagemagick\n"
      . "  Windows: choco install imagemagick\n";
}

# Detect at load time
_detect_cli();

# --- Temp file management ---

use File::Temp ();

my @ALL_TEMPS;  # safety net for END block

sub _new_temp {
    my $tmp = File::Temp->new(SUFFIX => '.miff', UNLINK => 0);
    my $path = $tmp->filename;
    close $tmp;
    push @ALL_TEMPS, $path;
    return $path;
}

END {
    for my $f (@ALL_TEMPS) {
        unlink $f if defined $f && -e $f;
    }
}

# --- Constructor ---

sub new {
    my $class = shift;
    $class = ref($class) || $class || 'Image::Magick';
    my $self = bless [], $class;
    # Internal state stored in a tied hash on the array
    # We use index -1 trick: store metadata in a hash ref at a known location
    # Actually, we store state in a package-scoped hash keyed by refaddr
    $self->Set(@_) if @_;
    return $self;
}

*New = \&new;

# --- Per-object state (keyed by refaddr) ---

my %STATE;  # refaddr => { attrs => {}, pending => [], sources => [] }

sub _state {
    my $self = shift;
    my $addr = Scalar::Util::refaddr($self);
    $STATE{$addr} ||= {
        attrs   => {},   # attributes set via Set()
        pending => [],   # queued CLI flags
        sources => [],   # source file paths (one per image in sequence)
    };
    return $STATE{$addr};
}

require Scalar::Util;

sub DESTROY {
    my $self = shift;
    my $addr = Scalar::Util::refaddr($self);
    if (my $st = delete $STATE{$addr}) {
        # Clean up temp files owned by this object
        for my $src (@{$st->{sources}}) {
            unlink $src if defined $src && $src =~ /\.miff$/ && -e $src;
        }
    }
}

# --- Error helpers ---

sub _ok { return _dual(0, '') }

sub _err {
    my ($code, $msg) = @_;
    return _dual($code, "Exception $code: $msg");
}

# Create a dual-valued scalar (integer + string)
sub _dual {
    my ($iv, $pv) = @_;
    my $sv = $pv;
    { no warnings 'numeric'; $sv += 0 if 0; }  # vivify IV slot
    # Use dualvar from Scalar::Util
    return Scalar::Util::dualvar($iv, $pv);
}

# --- Run a CLI command, return (exit_code, stdout, stderr) ---

sub _run_cmd {
    my @cmd = @_;
    # Use open3-like approach with temp files for portability
    my $out_file = File::Temp->new(SUFFIX => '.out', UNLINK => 1);
    my $err_file = File::Temp->new(SUFFIX => '.err', UNLINK => 1);
    my $out_path = $out_file->filename;
    my $err_path = $err_file->filename;
    close $out_file;
    close $err_file;

    # Build shell command with redirections
    # We use system() with a shell to get stdout/stderr redirection
    my $cmd_str = join(' ', map { _shell_quote($_) } @cmd);
    system("$cmd_str >$out_path 2>$err_path");
    my $exit = $?;

    my $stdout = '';
    my $stderr = '';
    if (open my $fh, '<', $out_path) { local $/; $stdout = <$fh> // ''; close $fh; }
    if (open my $fh, '<', $err_path) { local $/; $stderr = <$fh> // ''; close $fh; }
    unlink $out_path, $err_path;

    return ($exit, $stdout, $stderr);
}

sub _shell_quote {
    my $s = shift;
    if ($^O eq 'MSWin32') {
        $s =~ s/"/\\"/g;
        return qq{"$s"};
    }
    $s =~ s/'/'\\''/g;
    return "'$s'";
}

# --- Flush: execute queued operations ---

sub _flush {
    my ($self) = @_;
    my $st = $self->_state;

    return unless @{$st->{pending}} || !@{$st->{sources}};

    # If no sources and no pending ops, nothing to do
    return unless @{$st->{sources}};

    # Build command: convert_cmd source [pending ops] output
    my $output = _new_temp();

    my @cmd;
    my @cmd_parts = split(/\s+/, $CONVERT_CMD);
    push @cmd, @cmd_parts;

    # Add sources
    push @cmd, @{$st->{sources}};

    # Add queued operations
    push @cmd, @{$st->{pending}};

    # Add output
    push @cmd, $output;

    my ($exit, $stdout, $stderr) = _run_cmd(@cmd);

    if ($exit != 0) {
        return _err(OptionError, "magick command failed: $stderr");
    }

    # Update state: output becomes new source, clear pending
    @{$st->{sources}} = ($output);
    @{$st->{pending}} = ();

    # Update the array (one image for now; multi-frame support later)
    @$self = ($output);

    return;
}

# --- Read ---

sub Read {
    my $self = shift;
    my $st   = $self->_state;

    # Parse arguments: can be filenames or file=>$fh or filename=>$name
    my @files;
    my %opts;
    while (@_) {
        if ($_[0] =~ /^(file|filename)$/i && @_ >= 2) {
            my $key = shift;
            my $val = shift;
            $opts{lc $key} = $val;
        } else {
            push @files, shift;
        }
    }

    if ($opts{filename}) {
        push @files, $opts{filename};
    }

    # For file handles, write to temp first
    if ($opts{file}) {
        my $tmp = _new_temp();
        open my $out, '>', $tmp or return _err(FileOpenError, "Cannot write temp: $!");
        binmode $out;
        my $fh = $opts{file};
        while (read($fh, my $buf, 8192)) {
            print $out $buf;
        }
        close $out;
        push @files, $tmp;
    }

    return _err(OptionError, "No filename specified") unless @files;

    # Check files exist (unless pseudo-image like 'logo:' or 'xc:white')
    for my $f (@files) {
        next if $f =~ /^[a-z]+:/i;  # pseudo-image
        next if $f eq '-';            # stdin
        unless (-e $f) {
            return _err(FileOpenError, "unable to open image '$f': No such file or directory");
        }
    }

    # Convert each file to a temp MIFF for our pipeline
    for my $f (@files) {
        my $tmp = _new_temp();

        # Apply any pre-read attributes (size, etc.)
        my @pre_flags;
        if (my $size = $st->{attrs}{size}) {
            push @pre_flags, '-size', $size;
        }

        my @cmd = split(/\s+/, $CONVERT_CMD);
        push @cmd, @pre_flags, $f, $tmp;

        my ($exit, $stdout, $stderr) = _run_cmd(@cmd);
        if ($exit != 0) {
            return _err(FileOpenError, "unable to read '$f': $stderr");
        }

        push @{$st->{sources}}, $tmp;
        push @$self, $tmp;
    }

    return _ok();
}

*ReadImage = \&Read;
*read      = \&Read;
*readimage = \&Read;

# --- Write ---

sub Write {
    my $self = shift;
    my $st   = $self->_state;

    # Parse arguments
    my %opts;
    my $filename;
    while (@_) {
        if ($_[0] =~ /^(file|filename|quality|compression|depth|magick)$/i && @_ >= 2) {
            my $key = shift;
            my $val = shift;
            $opts{lc $key} = $val;
        } else {
            $filename = shift;
        }
    }
    $filename //= $opts{filename};
    return _err(OptionError, "No filename specified") unless defined $filename;

    # Flush pending operations
    my $err = $self->_flush;
    return $err if defined $err && "$err";

    return _err(OptionError, "No image to write") unless @{$st->{sources}};

    # Build write command with any output attributes
    my @cmd = split(/\s+/, $CONVERT_CMD);
    push @cmd, @{$st->{sources}};

    push @cmd, '-quality', $opts{quality} if defined $opts{quality};
    push @cmd, '-compress', $opts{compression} if defined $opts{compression};
    push @cmd, '-depth', $opts{depth} if defined $opts{depth};

    push @cmd, $filename;

    my ($exit, $stdout, $stderr) = _run_cmd(@cmd);
    if ($exit != 0) {
        return _err(BlobError, "unable to write '$filename': $stderr");
    }

    return _ok();
}

*WriteImage = \&Write;
*write      = \&Write;
*writeimage = \&Write;

# --- Ping ---

sub Ping {
    my $self = shift;
    my @files = @_;

    my @cmd = split(/\s+/, $IDENTIFY_CMD);
    push @cmd, '-format', '%w %h %b %m\n', @files;

    my ($exit, $stdout, $stderr) = _run_cmd(@cmd);
    if ($exit != 0) {
        return _err(FileOpenError, "unable to ping: $stderr");
    }

    # Parse: width height filesize format
    my @lines = split /\n/, $stdout;
    if (@lines && $lines[0] =~ /^(\d+)\s+(\d+)\s+(\S+)\s+(\S+)/) {
        return ($1, $2, $3, $4);
    }

    return _err(CorruptImageError, "unable to parse identify output");
}

*PingImage = \&Ping;
*ping      = \&Ping;
*pingimage = \&Ping;

# --- Set ---

sub Set {
    my $self = shift;
    my $st   = $self->_state;

    # Single argument = size
    if (@_ == 1) {
        $st->{attrs}{size} = $_[0];
        return _ok();
    }

    my %args = @_;
    for my $key (keys %args) {
        $st->{attrs}{lc $key} = $args{$key};
    }

    return _ok();
}

*SetAttribute  = \&Set;
*SetAttributes = \&Set;
*set           = \&Set;
*setattribute  = \&Set;
*setattributes = \&Set;

# --- Get ---

sub Get {
    my ($self, @names) = @_;
    my $st   = $self->_state;

    # Some attributes are available without flushing
    my %static = (
        version      => "PerlOnJava Image::Magick $VERSION (CLI wrapper)",
        copyright    => 'Copyright (C) 1999 ImageMagick Studio LLC',
    );

    # For image-dependent attributes, we need to flush and run identify
    my @results;
    my $need_identify = 0;

    for my $name (@names) {
        my $lc = lc $name;
        if (exists $static{$lc}) {
            push @results, $static{$lc};
        } elsif (exists $st->{attrs}{$lc}) {
            push @results, $st->{attrs}{$lc};
        } else {
            $need_identify = 1;
            push @results, undef;  # placeholder
        }
    }

    if ($need_identify && @{$st->{sources}}) {
        # Flush pending ops so identify sees current state
        my $err = $self->_flush;
        return (map { undef } @names) if defined $err && "$err";

        # Build identify format string
        my %fmt_map = (
            columns        => '%w',
            width          => '%w',
            rows           => '%h',
            height         => '%h',
            'x-resolution' => '%x',
            'y-resolution' => '%y',
            depth          => '%z',
            magick         => '%m',
            format         => '%r',
            filesize       => '%b',
            colorspace     => '%[colorspace]',
            type           => '%[type]',
            class          => '%r',
            colors         => '%k',
            compression    => '%C',
            signature      => '%#',
            geometry       => '%wx%h',
            size           => '%wx%h',
        );

        # Run identify for each requested attribute
        for my $i (0 .. $#names) {
            next if defined $results[$i];  # already resolved
            my $lc = lc $names[$i];

            my $fmt = $fmt_map{$lc};
            if (defined $fmt) {
                my @cmd = split(/\s+/, $IDENTIFY_CMD);
                push @cmd, '-format', $fmt, $st->{sources}[0];
                my ($exit, $stdout, $stderr) = _run_cmd(@cmd);
                if ($exit == 0) {
                    chomp $stdout;
                    # Convert numeric values
                    if ($lc =~ /^(columns|width|rows|height|depth|colors|filesize)$/) {
                        $stdout =~ s/[^0-9]//g if $lc ne 'filesize';
                        $results[$i] = $stdout + 0;
                    } else {
                        $results[$i] = $stdout;
                    }
                }
            }
        }
    }

    return wantarray ? @results : $results[0];
}

*GetAttribute  = \&Get;
*GetAttributes = \&Get;
*get           = \&Get;
*getattribute  = \&Get;
*getattributes = \&Get;

# --- Clone ---

sub Clone {
    my $self = shift;
    my $st   = $self->_state;

    # Flush so we have a materialized image
    my $err = $self->_flush;
    return _err(ImageError, "Clone failed: $err") if defined $err && "$err";

    my $clone = Image::Magick->new();
    my $cst   = $clone->_state;

    # Copy temp files
    for my $src (@{$st->{sources}}) {
        my $tmp = _new_temp();
        require File::Copy;
        File::Copy::copy($src, $tmp)
            or return _err(FileOpenError, "Clone copy failed: $!");
        push @{$cst->{sources}}, $tmp;
        push @$clone, $tmp;
    }

    # Copy attributes
    %{$cst->{attrs}} = %{$st->{attrs}};

    return $clone;
}

*CopyImage  = \&Clone;
*CloneImage = \&Clone;
*clone      = \&Clone;
*cloneimage = \&Clone;
*copy       = \&Clone;
*copyimage  = \&Clone;

# --- Composite ---

sub Composite {
    my $self = shift;
    my $st   = $self->_state;
    my %args = @_;

    # Flush base image
    my $err = $self->_flush;
    return $err if defined $err && "$err";

    return _err(OptionError, "No base image") unless @{$st->{sources}};

    # Get overlay image
    my $overlay = $args{image};
    return _err(OptionError, "No overlay image specified") unless $overlay;

    my $overlay_st = $overlay->_state;
    $overlay->_flush;
    return _err(OptionError, "Overlay has no image") unless @{$overlay_st->{sources}};

    my $output = _new_temp();
    my @cmd = split(/\s+/, $COMPOSITE_CMD);

    # Add compose method
    if (my $compose = $args{compose}) {
        push @cmd, '-compose', $compose;
    }

    # Add geometry/offset
    if (my $geom = $args{geometry}) {
        push @cmd, '-geometry', $geom;
    } elsif (defined $args{x} || defined $args{y}) {
        my $x = $args{x} // 0;
        my $y = $args{y} // 0;
        my $sign_x = $x >= 0 ? '+' : '';
        my $sign_y = $y >= 0 ? '+' : '';
        push @cmd, '-geometry', "${sign_x}${x}${sign_y}${y}";
    }

    # Add gravity
    push @cmd, '-gravity', $args{gravity} if $args{gravity};

    push @cmd, $overlay_st->{sources}[0], $st->{sources}[0], $output;

    my ($exit, $stdout, $stderr) = _run_cmd(@cmd);
    if ($exit != 0) {
        return _err(DelegateError, "composite failed: $stderr");
    }

    @{$st->{sources}} = ($output);
    @{$st->{pending}} = ();
    @$self = ($output);

    return _ok();
}

*CompositeImage = \&Composite;
*composite      = \&Composite;

# --- Montage ---

sub Montage {
    my $self = shift;
    my $st   = $self->_state;
    my %args = @_;

    # Flush
    my $err = $self->_flush;
    return _err(ImageError, "Montage failed: $err") if defined $err && "$err";

    return _err(OptionError, "No images for montage") unless @{$st->{sources}};

    my $output = _new_temp();
    my @cmd = split(/\s+/, $MONTAGE_CMD);

    # Montage options
    push @cmd, '-geometry', $args{geometry}   if $args{geometry};
    push @cmd, '-tile', $args{tile}           if $args{tile};
    push @cmd, '-background', $args{background} if $args{background};
    push @cmd, '-title', $args{title}         if $args{title};
    push @cmd, '-frame', $args{frame}         if $args{frame};
    push @cmd, '-shadow'                      if $args{shadow} && $args{shadow} =~ /^(true|1)$/i;
    push @cmd, '-label', $args{label}         if $args{label};
    push @cmd, '-font', $args{font}           if $args{font};
    push @cmd, '-pointsize', $args{pointsize} if $args{pointsize};
    push @cmd, '-gravity', $args{gravity}     if $args{gravity};
    push @cmd, '-mode', $args{mode}           if $args{mode};

    push @cmd, @{$st->{sources}};
    push @cmd, $output;

    my ($exit, $stdout, $stderr) = _run_cmd(@cmd);
    if ($exit != 0) {
        return _err(DelegateError, "montage failed: $stderr");
    }

    # Return new Image::Magick object
    my $result = Image::Magick->new();
    my $rst = $result->_state;
    push @{$rst->{sources}}, $output;
    push @$result, $output;

    return $result;
}

*MontageImage = \&Montage;
*montage      = \&Montage;
*montageimage = \&Montage;

# --- ImageToBlob ---

sub ImageToBlob {
    my $self = shift;
    my $st   = $self->_state;
    my %args = @_;

    my $err = $self->_flush;
    return () if defined $err && "$err";

    return () unless @{$st->{sources}};

    my $format = $args{magick} || $args{format} || 'PNG';
    my $tmp = File::Temp->new(SUFFIX => ".$format", UNLINK => 1);
    my $tmp_path = $tmp->filename;
    close $tmp;

    my @cmd = split(/\s+/, $CONVERT_CMD);
    push @cmd, $st->{sources}[0], "$format:$tmp_path";

    my ($exit, $stdout, $stderr) = _run_cmd(@cmd);
    if ($exit != 0) {
        return ();
    }

    open my $fh, '<:raw', $tmp_path or return ();
    local $/;
    my $blob = <$fh>;
    close $fh;
    unlink $tmp_path;

    return ($blob);
}

*imagetoblob = \&ImageToBlob;
*toblob      = \&ImageToBlob;
*blob        = \&ImageToBlob;

# --- BlobToImage ---

sub BlobToImage {
    my $self = shift;
    my $st   = $self->_state;

    for my $blob (@_) {
        my $tmp_in = File::Temp->new(SUFFIX => '.blob', UNLINK => 1);
        binmode $tmp_in;
        print $tmp_in $blob;
        my $in_path = $tmp_in->filename;
        close $tmp_in;

        my $tmp_out = _new_temp();
        my @cmd = split(/\s+/, $CONVERT_CMD);
        push @cmd, $in_path, $tmp_out;

        my ($exit, $stdout, $stderr) = _run_cmd(@cmd);
        unlink $in_path;
        if ($exit != 0) {
            return _err(BlobError, "BlobToImage failed: $stderr");
        }

        push @{$st->{sources}}, $tmp_out;
        push @$self, $tmp_out;
    }

    return _ok();
}

*blobtoimage = \&BlobToImage;
*blobto      = \&BlobToImage;

# --- Mogrify (generic dispatch) ---

# Map of method name => CLI flag
my %MOGRIFY_MAP = (
    'Blur'              => ['-blur',               'geometry|radius,sigma'],
    'GaussianBlur'      => ['-gaussian-blur',      'geometry|radius,sigma'],
    'MotionBlur'        => ['-motion-blur',        'geometry|radius,sigma,angle'],
    'Sharpen'           => ['-sharpen',            'geometry|radius,sigma'],
    'UnsharpMask'       => ['-unsharp',            'geometry|radius,sigma,gain,threshold'],
    'AdaptiveBlur'      => ['-adaptive-blur',      'geometry|radius,sigma'],
    'AdaptiveSharpen'   => ['-adaptive-sharpen',   'geometry|radius,sigma'],
    'Despeckle'         => ['-despeckle',          ''],
    'Enhance'           => ['-enhance',            ''],
    'ReduceNoise'       => ['-enhance',            ''],  # IM7 uses enhance
    'Resize'            => ['-resize',             'geometry|width,height'],
    'Scale'             => ['-scale',              'geometry|width,height'],
    'Sample'            => ['-sample',             'geometry|width,height'],
    'Thumbnail'         => ['-thumbnail',          'geometry|width,height'],
    'AdaptiveResize'    => ['-adaptive-resize',    'geometry|width,height'],
    'Crop'              => ['-crop',               'geometry|width,height,x,y'],
    'Chop'              => ['-chop',               'geometry|width,height,x,y'],
    'Extent'            => ['-extent',             'geometry|width,height'],
    'Trim'              => ['-trim',               ''],
    'Flip'              => ['-flip',               ''],
    'Flop'              => ['-flop',               ''],
    'Rotate'            => ['-rotate',             'degrees'],
    'Shear'             => ['-shear',              'geometry|x,y'],
    'Roll'              => ['-roll',               'geometry|x,y'],
    'Transpose'         => ['-transpose',          ''],
    'Transverse'        => ['-transverse',         ''],
    'AutoOrient'        => ['-auto-orient',        ''],
    'Strip'             => ['-strip',              ''],
    'Negate'            => ['-negate',             ''],
    'Normalize'         => ['-normalize',          ''],
    'Equalize'          => ['-equalize',           ''],
    'Contrast'          => ['-contrast',           ''],
    'AutoGamma'         => ['-auto-gamma',         ''],
    'AutoLevel'         => ['-auto-level',         ''],
    'Modulate'          => ['-modulate',           'brightness,saturation,hue'],
    'Gamma'             => ['-gamma',              'gamma'],
    'Level'             => ['-level',              'levels'],
    'BrightnessContrast'=> ['-brightness-contrast','brightness,contrast'],
    'SigmoidalContrast' => ['-sigmoidal-contrast', 'geometry|contrast,mid-point'],
    'Posterize'         => ['-posterize',          'levels'],
    'Solarize'          => ['-solarize',           'threshold'],
    'Threshold'         => ['-threshold',          'threshold'],
    'BlackThreshold'    => ['-black-threshold',    'threshold'],
    'WhiteThreshold'    => ['-white-threshold',    'threshold'],
    'Clamp'             => ['-clamp',              ''],
    'Colorize'          => ['-colorize',           'blend'],
    'Colorspace'        => ['-colorspace',         'colorspace'],
    'Grayscale'         => ['-grayscale',          'method'],
    'Quantize'          => ['-quantize',           'colorspace'],
    'Annotate'          => ['-annotate',           '_annotate'],
    'Draw'              => ['-draw',               '_draw'],
    'Border'            => ['-border',             'geometry|width,height'],
    'Frame'             => ['-frame',              'geometry|width,height,outer,inner'],
    'Raise'             => ['-raise',              'geometry|width,height'],
    'Shade'             => ['-shade',              'geometry|azimuth,elevation'],
    'Charcoal'          => ['-charcoal',           'geometry|radius,sigma'],
    'Edge'              => ['-edge',               'radius'],
    'Emboss'            => ['-emboss',             'radius'],
    'Implode'           => ['-implode',            'amount'],
    'OilPaint'          => ['-paint',              'radius'],
    'Spread'            => ['-spread',             'radius'],
    'Swirl'             => ['-swirl',              'degrees'],
    'Wave'              => ['-wave',               'geometry|amplitude,wavelength'],
    'Vignette'          => ['-vignette',           'geometry|radius,sigma,x,y'],
    'Sepia'             => ['-sepia-tone',         'threshold'],
    'SepiaTone'         => ['-sepia-tone',         'threshold'],
    'Shadow'            => ['-shadow',             'geometry|opacity,sigma,x,y'],
    'Sketch'            => ['-sketch',             'geometry|radius,sigma,angle'],
    'Stegano'           => ['-stegano',            'offset'],
    'Tint'              => ['-tint',               'fill'],
    'Transparent'       => ['-transparent',        'color'],
    'AddNoise'          => ['-noise',              'noise'],
    'Deskew'            => ['-deskew',             'threshold'],
    'Signature'         => ['-identify',           ''],  # triggers SHA digest
    'Magnify'           => ['-magnify',            ''],
    'Minify'            => ['-minify',             ''],
    'Unique'            => ['-unique-colors',      ''],
    'Shave'             => ['-shave',              'geometry|width,height'],
    'Splice'            => ['-splice',             'geometry|width,height,x,y'],
    'WhiteBalance'      => ['-white-balance',      ''],
);

sub Mogrify {
    my $self = shift;
    my $method = shift;
    return $self->_do_mogrify($method, @_);
}

*MogrifyImage = \&Mogrify;
*mogrify      = \&Mogrify;

sub _do_mogrify {
    my ($self, $method, @args) = @_;
    my $st = $self->_state;

    my $entry = $MOGRIFY_MAP{$method};
    return _err(OptionError, "Unrecognized method: $method") unless $entry;

    my ($flag, $param_spec) = @$entry;

    if ($param_spec eq '') {
        # No-argument flag
        push @{$st->{pending}}, $flag;
        return _ok();
    }

    # Parse arguments
    my %args;
    if (@args == 1 && !ref $args[0]) {
        # Single positional argument (e.g., Crop('100x100+10+10'))
        # Use as geometry or first named param
        my $val = $args[0];
        if ($param_spec eq '_annotate') {
            return $self->_do_annotate(text => $val);
        } elsif ($param_spec eq '_draw') {
            return $self->_do_draw(primitive => $val);
        }
        push @{$st->{pending}}, $flag, $val;
        return _ok();
    }

    %args = @args;

    # Special handlers
    if ($param_spec eq '_annotate') {
        return $self->_do_annotate(%args);
    }
    if ($param_spec eq '_draw') {
        return $self->_do_draw(%args);
    }

    # Build geometry or value from named params
    my $value;
    if (defined $args{geometry}) {
        $value = $args{geometry};
    } elsif ($param_spec =~ /^geometry\|(.+)/) {
        my @parts = split /,/, $1;
        my @vals;
        for my $p (@parts) {
            last unless defined $args{$p};
            push @vals, $args{$p};
        }
        if (@vals) {
            $value = join('x', @vals[0..min(1,$#vals)]);
            # Add offset for x,y params
            if (@vals > 2) {
                my $x = $vals[2] // 0;
                my $y = $vals[3] // 0;
                my $sx = $x >= 0 ? '+' : '';
                my $sy = $y >= 0 ? '+' : '';
                $value .= "${sx}${x}${sy}${y}";
            }
        }
    } else {
        # Named params matching spec
        my @parts = split /,/, $param_spec;
        for my $p (@parts) {
            if (defined $args{$p}) {
                $value = $args{$p};
                last;
            }
        }
    }

    if (defined $value) {
        push @{$st->{pending}}, $flag, "$value";
    } else {
        push @{$st->{pending}}, $flag;
    }

    return _ok();
}

sub min { $_[0] < $_[1] ? $_[0] : $_[1] }

# --- Annotate ---

sub _do_annotate {
    my ($self, %args) = @_;
    my $st = $self->_state;

    my $text = $args{text} // '';

    # Build annotate command
    push @{$st->{pending}}, '-font', $args{font} if $args{font};
    push @{$st->{pending}}, '-pointsize', $args{pointsize} if $args{pointsize};
    push @{$st->{pending}}, '-fill', $args{fill} if $args{fill};
    push @{$st->{pending}}, '-stroke', $args{stroke} if $args{stroke};
    push @{$st->{pending}}, '-strokewidth', $args{strokewidth} if $args{strokewidth};
    push @{$st->{pending}}, '-gravity', $args{gravity} if $args{gravity};
    push @{$st->{pending}}, '-undercolor', $args{undercolor} if $args{undercolor};
    push @{$st->{pending}}, '-kerning', $args{kerning} if $args{kerning};

    my $geom = '+0+0';
    if (defined $args{x} || defined $args{y}) {
        my $x = $args{x} // 0;
        my $y = $args{y} // 0;
        my $sx = $x >= 0 ? '+' : '';
        my $sy = $y >= 0 ? '+' : '';
        $geom = "${sx}${x}${sy}${y}";
    } elsif ($args{geometry}) {
        $geom = $args{geometry};
    }

    push @{$st->{pending}}, '-annotate', $geom, $text;

    return _ok();
}

# --- Draw ---

sub _do_draw {
    my ($self, %args) = @_;
    my $st = $self->_state;

    push @{$st->{pending}}, '-fill', $args{fill} if $args{fill};
    push @{$st->{pending}}, '-stroke', $args{stroke} if $args{stroke};
    push @{$st->{pending}}, '-strokewidth', $args{strokewidth} if $args{strokewidth};

    my $primitive = $args{primitive} // 'point';
    my $points    = $args{points}    // '0,0';
    push @{$st->{pending}}, '-draw', "$primitive $points";

    return _ok();
}

# --- Compare ---

sub Compare {
    my $self = shift;
    my %args = @_;
    my $st   = $self->_state;

    my $other = $args{image};
    return _err(OptionError, "No comparison image") unless $other;

    $self->_flush;
    $other->_flush;

    my $other_st = $other->_state;
    return _err(OptionError, "No images to compare") unless @{$st->{sources}} && @{$other_st->{sources}};

    my $metric = $args{metric} || 'RMSE';
    my $output = _new_temp();

    my @cmd = split(/\s+/, $CONVERT_CMD);
    push @cmd, '-metric', $metric, $st->{sources}[0], $other_st->{sources}[0], $output;

    # compare writes metric to stderr
    my ($exit, $stdout, $stderr) = _run_cmd(@cmd);

    # Create result image
    my $result = Image::Magick->new();
    my $rst = $result->_state;
    if (-e $output) {
        push @{$rst->{sources}}, $output;
        push @$result, $output;
    }

    # Parse error metric from stderr
    if ($stderr =~ /([\d.]+(?:e[+-]?\d+)?)/) {
        $rst->{attrs}{error} = $1 + 0;
    }

    return $result;
}

*CompareImages = \&Compare;
*compare       = \&Compare;
*compareimage  = \&Compare;

# --- Append ---

sub Append {
    my $self = shift;
    my $st   = $self->_state;
    my %args = @_;

    $self->_flush;
    return _err(OptionError, "No images to append") unless @{$st->{sources}};

    my $output = _new_temp();
    my @cmd = split(/\s+/, $CONVERT_CMD);
    push @cmd, @{$st->{sources}};
    push @cmd, ($args{stack} && $args{stack} =~ /^(true|1)$/i) ? '-append' : '+append';
    push @cmd, $output;

    my ($exit, $stdout, $stderr) = _run_cmd(@cmd);
    if ($exit != 0) {
        return _err(ImageError, "Append failed: $stderr");
    }

    my $result = Image::Magick->new();
    my $rst = $result->_state;
    push @{$rst->{sources}}, $output;
    push @$result, $output;
    return $result;
}

*AppendImage = \&Append;
*append      = \&Append;
*appendimage = \&Append;

# --- Flatten ---

sub Flatten {
    my $self = shift;
    my $st   = $self->_state;
    my %args = @_;

    $self->_flush;
    return _err(OptionError, "No images to flatten") unless @{$st->{sources}};

    my $output = _new_temp();
    my @cmd = split(/\s+/, $CONVERT_CMD);
    push @cmd, @{$st->{sources}};
    push @cmd, '-background', $args{background} if $args{background};
    push @cmd, '-flatten', $output;

    my ($exit, $stdout, $stderr) = _run_cmd(@cmd);
    if ($exit != 0) {
        return _err(ImageError, "Flatten failed: $stderr");
    }

    my $result = Image::Magick->new();
    my $rst = $result->_state;
    push @{$rst->{sources}}, $output;
    push @$result, $output;
    return $result;
}

*FlattenImage = \&Flatten;
*flatten      = \&Flatten;
*flattenimage = \&Flatten;

# --- Unsupported methods (die with clear error) ---

for my $unsupported (qw(
    Display DisplayImage display displayimage
    Animate AnimateImage animate animateimage
    GetPixel getpixel getPixel
    SetPixel setpixel setPixel
    GetPixels getpixels getPixels
    SetPixels setpixels setPixels
    GetAuthenticPixels GetVirtualPixels
    GetAuthenticMetacontent GetVirtualMetacontent
    SyncAuthenticPixels
    RemoteCommand remote Remote
)) {
    no strict 'refs';
    *{$unsupported} = sub {
        die "Image::Magick::$unsupported() is not supported in the CLI wrapper.\n"
          . "This method requires direct pixel/display access not available via the magick CLI.\n";
    };
}

# --- AUTOLOAD for Mogrify aliases ---

sub AUTOLOAD {
    my $self = shift;
    our $AUTOLOAD;
    my $method = $AUTOLOAD;
    $method =~ s/.*:://;

    return if $method eq 'DESTROY';
    return if $method eq 'END';
    return if $method eq 'UNLOAD';

    # Strip trailing 'Image' suffix (e.g. CropImage -> Crop)
    my $base = $method;
    $base =~ s/Image$//;

    # Try case-insensitive match in MOGRIFY_MAP
    my $found;
    for my $key (keys %MOGRIFY_MAP) {
        if (lc $key eq lc $base) {
            $found = $key;
            last;
        }
    }

    if ($found) {
        return $self->_do_mogrify($found, @_);
    }

    die "Undefined Image::Magick method: $method\n";
}

# --- Coalesce ---

sub Coalesce {
    my $self = shift;
    my $st   = $self->_state;

    $self->_flush;
    return _err(OptionError, "No images") unless @{$st->{sources}};

    my $output = _new_temp();
    my @cmd = split(/\s+/, $CONVERT_CMD);
    push @cmd, @{$st->{sources}}, '-coalesce', $output;

    my ($exit, $stdout, $stderr) = _run_cmd(@cmd);
    if ($exit != 0) {
        return _err(ImageError, "Coalesce failed: $stderr");
    }

    my $result = Image::Magick->new();
    my $rst = $result->_state;
    push @{$rst->{sources}}, $output;
    push @$result, $output;
    return $result;
}

*CoalesceImage = \&Coalesce;
*coalesce      = \&Coalesce;
*coalesceimage = \&Coalesce;

1;

__END__

=head1 NAME

Image::Magick - PerlOnJava CLI wrapper for ImageMagick

=head1 SYNOPSIS

    use Image::Magick;

    my $img = Image::Magick->new;
    $img->Read('input.jpg');
    $img->Resize(geometry => '50%');
    $img->Write('output.png');

=head1 DESCRIPTION

This is a PerlOnJava-specific implementation of the Image::Magick API that
delegates to the ImageMagick CLI tools (C<magick> for IM7, C<convert>/C<identify>
for IM6). It provides the same API as the CPAN Image::Magick module but does not
require the native PerlMagick XS library.

Requires ImageMagick CLI tools to be installed and in PATH.

=head1 LIMITATIONS

The following methods are not supported and will die with a descriptive error:

=over 4

=item * Display(), Animate() — require X11

=item * GetPixel(), SetPixel(), GetPixels(), SetPixels() — per-pixel access

=item * GetAuthenticPixels(), GetVirtualPixels() — low-level C pointer access

=back

=head1 AUTHOR

Original PerlMagick by Kyle Shorter, ImageMagick Studio LLC.

CLI wrapper for PerlOnJava by the PerlOnJava project.

=head1 COPYRIGHT

Copyright (C) 1999 ImageMagick Studio LLC.

This is free software; you can redistribute it and/or modify it under
the same terms as Perl itself.

=cut
