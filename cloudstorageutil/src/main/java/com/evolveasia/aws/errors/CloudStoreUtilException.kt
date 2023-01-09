package com.evolveasia.aws.errors

import java.io.IOException

class ImageCorruptedException(var errorMsg: String): IOException()